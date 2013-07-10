package scala.slick
package migration.api

import org.scalatest.fixture
import org.scalatest.Inside
import org.scalatest.matchers.ShouldMatchers
import com.typesafe.slick.testkit.util._
import scala.slick.driver._
import scala.slick.lifted.ForeignKeyAction
import scala.slick.jdbc.meta._
import scala.slick.jdbc.JdbcBackend
import java.sql.Types
import java.sql.SQLException

object H2Mem extends JdbcTestDB("h2mem") {
  type Driver = H2Driver.type
  val driver = H2Driver
  val url = "jdbc:h2:mem:test1"
  val jdbcDriver = "org.h2.Driver"
  override def isPersistent = false
  override lazy val capabilities = driver.capabilities + TestDB.plainSql + TestDB.plainSqlWide
}

trait DbFixture { this: fixture.Suite =>
  class FixtureParam(driver: JdbcDriver, val session: JdbcDriver.simple.Session) extends Migrations(driver)
  implicit def sessForFP(implicit fp: FixtureParam): JdbcDriver.simple.Session = fp.session

  val dbs = List(H2Mem)

  def getTables(implicit session: JdbcBackend#Session) = MTable.getTables.list
  def getTable(name: String)(implicit session: JdbcBackend#Session) =
    getTables.find(_.name.name == name)

  def withFixture(test: OneArgTest) = for (tdb <- dbs) tdb.driver match {
    case driver: JdbcDriver =>
      tdb.cleanUpBefore()
      val db = tdb.createDB()
      try db.withSession { session: driver.simple.Session =>
        test(new FixtureParam(driver, session))
      } finally tdb.cleanUpAfter()
  }
}

class Test extends fixture.FunSuite with ShouldMatchers with Inside with DbFixture {
  test("& returns the right type and doesn't keep nesting") { fix =>
    import fix._
    val m = new Migration {
      def apply()(implicit s: driver.simple.Session) = ???
    }
    m & m & m should equal (MigrationSeq(m, m, m))

    val rm = new ReversibleMigration {
      def apply()(implicit s: driver.simple.Session) = ???
      def reverse = ???
    }

    val rms = rm & rm & rm
    val rms1: ReversibleMigrationSeq = rms // verify the type
    rms should equal (new ReversibleMigrationSeq(rm, rm, rm))
  }

  test("CreateTable, DropTable") { implicit fix =>
    import fix._
    import driver.simple._

    object table1 extends Table[(Long, Int)]("table1") {
      def id = column[Long]("id", O.NotNull, O.AutoInc)
      def col1 = column[Int]("col1", O.Default(10))
      def * = id ~ col1
    }

    val before = getTables

    val createTable = CreateTable(table1)(_.id, _.col1)

    createTable()

    val after = getTables

    inside(after filterNot before.contains) {
      case (table @ MTable(MQName(_, _, table1.tableName), "TABLE", _, _, _, _)) :: Nil =>
        val cols = table.getColumns.list
        cols.map {
          case col => (col.column, col.sqlType, col.nullable, col.isAutoInc)
        } should equal (List(
          ("id", Types.BIGINT, Some(false), Some(true)),
          ("col1", Types.INTEGER, Some(false), Some(false))
        ))
        cols.find(_.column == "col1").flatMap(_.columnDef) should equal (Some("10"))
    }

    createTable.reverse should equal (DropTable(table1))

    createTable.reverse()

    getTables should equal (before)
  }

  test("CreatePrimaryKey, DropPrimaryKey") { implicit fix =>
    import fix._
    import driver.simple._

    object table1 extends Table[(Long, String)]("table1") {
      def id = column[Long]("id")
      def stringId = column[String]("stringId")
      def * = id ~ stringId
      def pk = primaryKey("pk", id ~ stringId)
    }

    def pkList = getTable("table1").map(_.getPrimaryKeys.list) getOrElse Nil
    def pks = pkList
      .groupBy(_.pkName)
      .mapValues {
        _ collect { case MPrimaryKey(MQName(_, _, "table1"), col, seq, _) => (seq, col) }
      }

    CreateTable(table1)(_.id, _.stringId)()

    val before = pks

    before.get(Some("pk")) should equal (None)

    val createPrimaryKey = CreatePrimaryKey(table1)(_.pk)
    createPrimaryKey()

    pks(Some("pk")) should equal (List(1 -> "id", 2 -> "stringId"))

    //TODO this doesn't do much since case classes only compare the first parameter list
    createPrimaryKey.reverse should equal (DropPrimaryKey(table1)(_.pk))
    createPrimaryKey.reverse()

    pks should equal (before)
  }

  test("CreateForeignKey, DropForeignKey") { implicit fix =>
    import fix._
    import driver.simple._

    object table1 extends Table[(Long, Long)]("table1") {
      def id = column[Long]("id", O.PrimaryKey)
      def other = column[Long]("other")

      def * = id ~ other
      // not a def, so equality works
      lazy val fk = foreignKey("fk_other", other, table2)(_.id, ForeignKeyAction.Cascade, ForeignKeyAction.Cascade)
    }
    object table2 extends Table[Long]("table2") {
      def id = column[Long]("id", O.PrimaryKey)
      def * = id
    }

    CreateTable(table1)(_.id, _.other)()
    CreateTable(table2)(_.id)()

    def fks = getTables.to[Set] map { t =>
      (
        t.name.name,
        t.getImportedKeys.list map { fk =>
          (fk.pkTable.name, fk.pkColumn, fk.fkTable.name, fk.fkColumn, fk.updateRule, fk.deleteRule, fk.fkName)
        }
      )
    }

    val before = fks

    before should equal (Set(
      ("table1", Nil),
      ("table2", Nil)
    ))

    val createForeignKey = CreateForeignKey(table1.fk)
    createForeignKey()

    fks should equal (Set(
      ("table1", ("table2", "id", "table1", "other", ForeignKeyAction.Cascade, ForeignKeyAction.Cascade, Some("fk_other")) :: Nil),
      ("table2", Nil)
    ))

    val dropForeignKey = createForeignKey.reverse
    inside(dropForeignKey.migrations.toList) {
      case DropForeignKey(fk) :: Nil =>
        Seq(fk) should equal (table1.fk.fks)
    }

    dropForeignKey()

    fks should equal (before)
  }

  test("CreateIndex, DropIndex") { implicit fix =>
    import fix._
    import driver.simple._

    object table1 extends Table[(Long, Int, Int, Int)]("table1") {
      def id = column[Long]("id")
      def col1 = column[Int]("col1")
      def col2 = column[Int]("col2")
      def col3 = column[Int]("col3")
      def * = id ~ col1 ~ col2 ~ col3
      val index1 = index("index1", col1)
      val index2 = index("index2", col2 ~ col3, true)
    }

    def indexList = getTable("table1").map(_.getIndexInfo().list) getOrElse Nil
    def indexes = indexList
      .groupBy(i => (i.indexName, !i.nonUnique))
      .mapValues {
        _ collect {
          case MIndexInfo(MQName(_, _, "table1"), _, _, _, _, seq, col, _, _, _, _) =>
            (seq, col)
        }
      }

    CreateTable(table1)(_.id, _.col1, _.col2, _.col3)()

    val createIndexes = CreateIndex(table1.index1) & CreateIndex(table1.index2)
    createIndexes()

    indexes(Some("index1") -> false) should equal (List((1, Some("col1"))))
    indexes(Some("index2") -> true) should equal (List((1, Some("col2")), (2, Some("col3"))))

    createIndexes.reverse should equal (DropIndex(table1.index2) & DropIndex(table1.index1))

    createIndexes.reverse()
  }

  test("AddColumn, DropColumn") { implicit fix =>
    import fix._
    import driver.simple._

    object table1 extends Table[(Long, String)]("table1") {
      def col1 = column[Long]("col1")
      def col2 = column[String]("col2")
      def * = col1 ~ col2
    }

    CreateTable(table1)(_.col1)()

    def columnsCount = getTable("table1").map(_.getColumns.list.length)

    columnsCount should equal (Some(1))

    val addColumn = AddColumn(table1)(_.col2)
    addColumn()

    columnsCount should equal (Some(2))

    val dropColumn = addColumn.reverse
    dropColumn should equal (DropColumn(table1)(_.col2))

    dropColumn()

    columnsCount should equal (Some(1))
  }

  test("AlterColumnType/Default/Nullability") { implicit fix =>
    import fix._
    import driver.simple._

    object table1 extends Table[Long]("table1") {
      def id = column[Long]("id", O.Default(20), O.NotNull)
      def * = id
    }

    CreateTable(table1)(_.column[String]("id", table1.O.Nullable))()

    def columns = getTable("table1").map(_.getColumns.list.map {
      case col => (col.column, col.sqlType, col.columnDef)
    }) getOrElse Nil

    columns.toList should equal (List(("id", Types.VARCHAR, None)))

    try table1.insert(null)
    catch {
      case e: SQLException =>
        fail("Could not insert NULL")
    }
    Query(table1).delete

    val m = AlterColumnType(table1)(_.id) &
      AlterColumnDefault(table1)(_.id) &
      AlterColumnNullability(table1)(_.id)
    m()

    columns.toList should equal (List(("id", Types.BIGINT, Some("20"))))
    intercept[SQLException] {
      table1.insert(null)
    }
  }

  test("RenameTable/Column/Index") { implicit fix =>
    import fix._
    import driver.simple._

    trait tables { this: Table[Long] =>
      def col1 = column[Long]("oldname")
      def * = col1
      val index1 = index("oldname", col1)
    }
    object oldname extends Table[Long]("oldname") with tables
    object table1 extends Table[Long]("table1") with tables

    CreateTable(oldname)(_.col1).withIndexes(_.index1)()

    def tables = getTables.map(_.name.name)
    def columns = getTables.flatMap(_.getColumns.list.map(_.column))
    def indexes = getTables.flatMap(_.getIndexInfo().list.flatMap(_.indexName))

    tables should equal (List("oldname"))
    columns should equal (List("oldname"))
    indexes should equal (List("oldname"))

    RenameTable(oldname, "table1")()
    RenameColumn(table1)(_.col1, _.column[Long]("col1"))()
    RenameIndex(table1.index1, "index1")()

    tables should equal (List("table1"))
    columns should equal (List("col1"))
    indexes should equal (List("index1"))
  }
}
