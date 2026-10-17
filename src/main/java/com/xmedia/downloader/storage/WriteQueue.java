package com.xmedia.downloader.storage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单写线程队列：所有数据库写入经此串行化（H3）。
 *
 * <p>并发下载线程不直接写 SQLite（否则必然撞 {@code SQLITE_BUSY}，而写库失败会被误判成下载失败、
 * 污染 FAILED 记录）。下载线程只投递任务，落库由本队列的唯一线程执行。
 */
public final class WriteQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WriteQueue.class);
    private static final int CAPACITY = 10_000;

    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final Thread worker;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private volatile boolean closed;

    public WriteQueue() {
        worker = new Thread(this::drain, "xmd-db-writer");
        worker.setDaemon(true);
        worker.start();
    }

    private void drain() {
        while (!closed || !queue.isEmpty()) {
            try {
                Runnable task = queue.take();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                failure.compareAndSet(null, e);
                log.error("数据库写入任务失败（已记录，flush 时会抛出）", e);
            }
        }
    }

    /** 投递一个写入任务。 */
    public void submit(Runnable task) {
        if (closed) {
            throw new IllegalStateException("WriteQueue 已关闭");
        }
        try {
            queue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("投递写入任务被中断", e);
        }
    }

    /** 等待队列排空；期间若任一任务失败则抛出。 */
    public void flush() {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            queue.put(latch::countDown);
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待写入队列排空被中断", e);
        }
        RuntimeException e = failure.getAndSet(null);
        if (e != null) {
            throw e;
        }
    }

    public int pending() {
        return queue.size();
    }

    @Override
    public void close() {
        flush();
        closed = true;
        worker.interrupt();
    }
}
