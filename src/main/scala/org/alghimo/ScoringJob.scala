package org.alghimo

import java.util.concurrent.{ScheduledThreadPoolExecutor, TimeUnit}

import org.alghimo.models.TransactionScore
import org.alghimo.services.{ProductionScoreService, ScoreServiceProvider}
import org.apache.flink.api.common.JobExecutionResult
import org.apache.flink.api.common.accumulators.LongCounter
import org.apache.flink.api.common.functions.RichMapFunction
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.scala._
import org.slf4j.LoggerFactory

/**
  * Created by alghimo on 9/13/2016.
  */
abstract class AbstractScoringJob extends KafkaProperties with ScoreServiceProvider with java.io.Serializable {
    protected def getExecutionEnv(): StreamExecutionEnvironment

    def run(args: Array[String] = Array.empty): JobExecutionResult = {
        val env    = getExecutionEnv()
        val stream = doRun(env)

        env.execute("Score Transactions")
    }

    def doRun(env: StreamExecutionEnvironment): StreamExecutionEnvironment = {
        env
            .addSource(kafkaStringConsumer(TRANSACTIONS_TO_SCORE_TOPIC))
            .map(scoreService.scoreTransaction _)
            .filter(_.isDefined)
            .map(scoreToJsonMapper)
            .addSink(kafkaStringProducer(SCORED_TRANSACTIONS_TOPIC))

        env
    }

    protected final val scoreToJsonMapper = new RichMapFunction[Option[TransactionScore], String]() {
        private final val logger       = LoggerFactory.getLogger("org.alghimo.fraudpoc.transPerSecond")
        private val scoredTransactions = new LongCounter()
        private lazy val startTime     = System.currentTimeMillis()
        private var lastIntervalCount  = 0L
        private var statsScheduled     = false

        protected val task = (accum: LongCounter) => new Runnable {
            def run() = {
                if (accum.getLocalValue > lastIntervalCount) {
                    val ellapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    logger.info("Transacs last second: " + (accum.getLocalValue - lastIntervalCount) + " - AVG: " + (accum.getLocalValue / ellapsed))
                    lastIntervalCount = accum.getLocalValue
                }
            }
        }

        override def open(parameters: Configuration): Unit = getRuntimeContext.addAccumulator("scored-transactions", scoredTransactions)

        override def map(score: Option[TransactionScore]) = {
            if (!statsScheduled) {
                doSchedule(this.scoredTransactions)
            }

            this.scoredTransactions.add(1)

            gson.toJson(score.get)
        }

        private def doSchedule(accum: LongCounter) = {
            new ScheduledThreadPoolExecutor(1)
                .scheduleAtFixedRate(task(accum), 1, 1, TimeUnit.SECONDS)
            statsScheduled = true
        }
    }
}

object ScoringJob extends AbstractScoringJob with ProductionKafkaProperties with ProductionScoreService with java.io.Serializable {
    override protected def getExecutionEnv(): StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
}