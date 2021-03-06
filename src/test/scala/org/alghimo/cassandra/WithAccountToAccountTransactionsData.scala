package org.alghimo.cassandra

import com.websudos.phantom.dsl
import org.alghimo.models.AccountToAccountTransaction
import org.alghimo.utils.DatabaseTest

import scala.concurrent.{Await, Future, Promise}
/**
  * Created by alghimo on 11/18/2016.
  */
trait WithAccountToAccountTransactionsData
    extends DatabaseTest
    with TestDatabaseProvider
{
    import dsl.context

    def accountToAccountTransactionsData: Seq[AccountToAccountTransaction] = Seq()

    override def setupFixtures(): Unit = {
        super.setupFixtures()

        val p                                  = Promise[Future[Boolean]]()
        val accountToAccountTransactionFutures = accountToAccountTransactionsData
            .map(database.accountToAccountTransactions.store(_))

        accountToAccountTransactionFutures
            .foreach(_.onFailure {
                case ex => p.tryFailure(ex)
            })

        Await.result(Future.sequence(accountToAccountTransactionFutures), autoCreateTimeout)
    }

    override def cleanupFixtures(): Unit = {
        Await.result(database.accountToAccountTransactions.truncate().future(), autoDropTimeout)
        super.cleanupFixtures()
    }
}
