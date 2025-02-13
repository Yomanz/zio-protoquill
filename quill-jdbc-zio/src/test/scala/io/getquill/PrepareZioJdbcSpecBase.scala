package io.getquill

import io.getquill.context.ZioJdbc._
import io.getquill.context.jdbc.ResultSetExtractor
import io.getquill.context.sql.ProductSpec
import io.getquill.context.qzio.ZioJdbcContext
import org.scalactic.Equality
import zio.{ Runtime, Task, ZEnvironment, ZIO }
import io.getquill.generic.GenericDecoder
import io.getquill.generic.DecodingType.Generic

import java.sql.{ Connection, PreparedStatement, ResultSet }
import io.getquill.context.qzio.ImplicitSyntax.Implicit
import javax.sql.DataSource

trait PrepareZioJdbcSpecBase extends ProductSpec with ZioSpec {

  val context: ZioJdbcContext[_, _]
  import context._

  implicit val productEq: Equality[Product] = new Equality[Product] {
    override def areEqual(a: Product, b: Any): Boolean = b match {
      case Product(_, desc, sku) => desc == a.description && sku == a.sku
      case _                     => false
    }
  }

  def productExtractor = (rs: ResultSet, session: Session) => summon[GenericDecoder[context.ResultRow, context.Session, Product, Generic]](0, rs, session)

  def withOrderedIds(products: List[Product]) =
    products.zipWithIndex.map { case (product, id) => product.copy(id = id.toLong + 1) }

  def singleInsert(prep: QCIO[PreparedStatement])(implicit runtime: Implicit[Runtime.Scoped[DataSource]]) = {
    prep.flatMap(stmt =>
      ZIO.attempt(stmt).acquireReleaseWithAuto { stmt => ZIO.attempt(stmt.execute()) }).onDataSource.runSyncUnsafe()
  }

  def batchInsert(prep: QCIO[List[PreparedStatement]])(implicit runtime: Implicit[Runtime.Scoped[DataSource]]) =
    prep.flatMap(stmts =>
      ZIO.collectAll(
        stmts.map(stmt =>
          ZIO.attempt(stmt).acquireReleaseWithAuto { stmt => ZIO.attempt(stmt.execute()) })
      )).onDataSource.runSyncUnsafe()

  def extractResults[T](prepareStatement: QCIO[PreparedStatement])(extractor: (ResultSet, Connection) => T)(implicit runtime: Implicit[Runtime.Scoped[DataSource]]) =
    (for {
      conn <- ZIO.service[Connection]
      result <- prepareStatement.provideEnvironment(ZEnvironment(conn)).acquireReleaseWithAuto { stmt =>
        ZIO.attempt(stmt.executeQuery()).acquireReleaseWithAuto { rs =>
          ZIO.attempt(ResultSetExtractor(rs, stmt.getConnection, extractor))
        }
      }
    } yield result).onDataSource.runSyncUnsafe()

  def extractProducts(prep: QCIO[PreparedStatement])(implicit runtime: Implicit[Runtime.Scoped[DataSource]]) =
    extractResults(prep)(productExtractor)
}
