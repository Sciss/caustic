package caustic.runtime
package jdbc

import com.mchange.v2.c3p0.ComboPooledDataSource
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, Outcome}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

@RunWith(classOf[JUnitRunner])
class PostgresDatabaseTest extends DatabaseTest with BeforeAndAfterAll {

  var pool: ComboPooledDataSource = _

  override def beforeAll(): Unit = {
    this.pool = new ComboPooledDataSource()
    this.pool.setDriverClass("org.postgresql.Driver")
    this.pool.setJdbcUrl("jdbc:postgresql://localhost:5432/test?serverTimezone=UTC")
    this.pool.setUser("postgres")
    this.pool.setPassword("")
  }

  override def withFixture(test: OneArgTest): Outcome = {
    // Delete all the table metadata.
    val con = this.pool.getConnection()
    val smt = con.createStatement()
    smt.execute("DROP TABLE IF EXISTS caustic")
    con.close()

    // Run the tests.
    val database = JdbcDatabase(this.pool, PostgresDialect)
    Await.result(database.exists, 10 seconds)
    test(database)
  }

  override def afterAll(): Unit = {
    this.pool.close()
  }

}
