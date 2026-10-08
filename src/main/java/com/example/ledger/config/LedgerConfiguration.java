package com.example.ledger.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.ledger.concurrency.WriteQueue;
import com.example.ledger.persistence.SqliteDatabase;
import com.example.ledger.service.LedgerService;

/**
 * Creates the ledger's database, worker, clock, and application service.
 */
@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfiguration {
    /**
     * Creates the UTC clock used for financial posting and reconciliation cutoffs.
     *
     * @return System clock operating in UTC.
     */
    @Bean
    public Clock createClock() {
        return Clock.systemUTC();
    }

    /**
     * Opens the configured database and acquires exclusive application ownership.
     *
     * @param properties Startup configuration containing database settings and validated arithmetic limits.
     * @return Owned database with verified durability and connection settings.
     * @throws Exception if database ownership, file access, or required SQLite settings cannot be
     *     established.
     */
    @Bean(destroyMethod = "close")
    public SqliteDatabase createDatabase(LedgerProperties properties) throws Exception {
        return new SqliteDatabase(properties);
    }

    /**
     * Creates the bounded single writer with the configured drain deadline.
     *
     * @param database Owned database dependency ensuring the queue is destroyed before database ownership is
     *     released.
     * @param properties Startup configuration containing database settings and validated arithmetic limits.
     * @return Bounded FIFO worker configured with the shutdown drain deadline.
     */
    @Bean(destroyMethod = "close")
    public WriteQueue createWriteQueue(SqliteDatabase database, LedgerProperties properties) {
        return new WriteQueue(properties.getQueueCapacity(), properties.getShutdownTimeout());
    }

    /**
     * Creates the financial service after database and worker initialization.
     *
     * @param database Owned SQLite database supplying configured reader and writer connections.
     * @param queue Bounded single-worker queue used for financial writes.
     * @param properties Startup configuration containing database settings and validated arithmetic limits.
     * @param ledgerClock UTC clock supplying posting timestamps and reconciliation cutoffs.
     * @return Initialized financial service using the owned database and single writer.
     */
    @Bean
    public LedgerService createLedgerService(
            SqliteDatabase database,
            WriteQueue queue,
            LedgerProperties properties,
            Clock ledgerClock) {
        return new LedgerService(database, queue, properties, ledgerClock, transactionId -> {});
    }
}
