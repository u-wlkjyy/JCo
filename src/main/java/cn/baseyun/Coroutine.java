package cn.baseyun;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Java Coroutines (JCo)
 * 基于 JDK 21 Virtual Threads + StructuredTaskScope
 * <p>
 * 注意事项：
 * 本工具依赖 ThreadLocal 传递上下文。
 * 1. 必须在 runBlocking 内部使用 async/launch。
 * 2. 禁止在 async/launch 内部手动 new Thread() 或使用普通线程池，否则上下文会丢失。
 */
public class Coroutine {

    private static final ThreadLocal<StructuredTaskScope<Object>> SCOPE_CONTEXT = new ThreadLocal<>();

    // ==========================================
    // 1. 核心接口
    // ==========================================
    public interface Job<T> {
        T await();
        void cancel();
        boolean isDone();
    }

    // ==========================================
    // 2. 常用语法糖
    // ==========================================
    public static void repeat(int times, Consumer<Integer> action) {
        for (int i = 0; i < times; i++) {
            action.accept(i);
        }
    }

    /**
     * 超时控制
     * 原理：利用 race 机制，业务任务和定时异常任务赛跑
     */
    public static <T> T withTimeout(long millis, Callable<T> block) {
        return race(
                block,
                () -> {
                    delay(millis);
                    throw new TimeoutException("Operation timed out after " + millis + "ms");
                }
        );
    }

    // ==========================================
    // 3. 竞速模式 (区分语义)
    // ==========================================

    /**
     * 语义：【谁快选谁】(First Completion Wins)
     * 无论第一个完成的任务是成功还是失败，都会立即终止其他任务并返回结果（或抛出异常）。
     * 场景：超时控制、对等节点的非高可用调用。
     */
    @SafeVarargs
    public static <T> T race(Callable<T>... tasks) {
        // 使用自定义 Scope：只要有一个由于任何原因结束，就关闭 Scope
        try (var scope = new ShutdownOnFirst<T>()) {
            for (Callable<T> task : tasks) scope.fork(task);
            scope.join();
            return scope.result();
        } catch (ExecutionException e) {
            unwrapAndThrow(e);
            return null; // unreachable
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * 语义：【谁成选谁】(First Success Wins)
     * 忽略失败的任务，直到有一个任务成功为止。只有当所有任务都失败时，才会抛出异常。
     * 场景：高可用调用 (HA)，同时查 A库、B库、C库，只要有一个能给数据就行。
     */
    @SafeVarargs
    public static <T> T raceSuccess(Callable<T>... tasks) {
        // 使用 JDK 原生 Scope：ShutdownOnSuccess
        try (var scope = new StructuredTaskScope.ShutdownOnSuccess<T>()) {
            for (Callable<T> task : tasks) scope.fork(task);
            scope.join();
            return scope.result();
        } catch (ExecutionException e) {
            // 这里抛出异常意味着“所有任务都失败了”
            // JDK 会把最后一个失败的异常作为 cause，或者抛出 IllegalStateException
            unwrapAndThrow(e);
            return null; // unreachable
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ==========================================
    // 4. 基础启动器
    // ==========================================
    public static void runBlocking(Runnable block) {
        withScope(new StructuredTaskScope.ShutdownOnFailure(), () -> { block.run(); return null; }, true);
    }

    public static void supervisorScope(Runnable block) {
        withScope(new StructuredTaskScope<Object>(), () -> { block.run(); return null; }, false);
    }

    public static void launch(Runnable block) {
        async(() -> { block.run(); return null; });
    }

    public static void launch(String name, Runnable block) {
        async(() -> {
            String oldName = Thread.currentThread().getName();
            Thread.currentThread().setName(name);
            try { block.run(); return null; } finally { Thread.currentThread().setName(oldName); }
        });
    }

    public static <T> Job<T> async(Callable<T> block) {
        StructuredTaskScope<Object> scope = getScopeOrThrow();

        CompletableFuture<T> future = new CompletableFuture<>();
        AtomicReference<Thread> worker = new AtomicReference<>();

        scope.fork(() -> {
            worker.set(Thread.currentThread());
            try {
                T res = withScope(new StructuredTaskScope.ShutdownOnFailure(), block::call, true);
                future.complete(res);
                return res;
            } catch (Throwable t) {
                future.completeExceptionally(t);
                throw t;
            }
        });

        return new Job<>() {
            public T await() {
                try {
                    return future.get();
                } catch (InterruptedException e) { throw new RuntimeException(e); }
                catch (ExecutionException e) { unwrapAndThrow(e); return null; }
            }
            public void cancel() {
                if (future.cancel(true)) {
                    Thread t = worker.get();
                    if (t != null) t.interrupt();
                }
            }
            public boolean isDone() { return future.isDone(); }
        };
    }

    // ==========================================
    // 5. 集合并发
    // ==========================================
    public static <T, R> Batch<R> map(Collection<T> list, Function<T, R> mapper) {
        List<Job<R>> jobs = new ArrayList<>(list.size());
        for (T item : list) jobs.add(async(() -> mapper.apply(item)));
        return new Batch<>(jobs);
    }

    public static class Batch<T> {
        private final List<Job<T>> jobs;
        public Batch(List<Job<T>> jobs) { this.jobs = jobs; }
        public List<T> awaitAll() {
            List<T> res = new ArrayList<>(jobs.size());
            for (Job<T> j : jobs) res.add(j.await());
            return res;
        }
        public void cancelAll() { for (Job<T> j : jobs) j.cancel(); }
    }

    // ==========================================
    // 6. 辅助工具 & 内部实现
    // ==========================================

    // 获取上下文，如果丢失则抛出详细错误
    private static StructuredTaskScope<Object> getScopeOrThrow() {
        StructuredTaskScope<Object> scope = SCOPE_CONTEXT.get();
        if (scope == null) {
            throw new IllegalStateException(
                    "Coroutine Context Lost! \n" +
                            "Cause: You are trying to call 'async/launch' outside of a 'runBlocking' scope.\n" +
                            "Common mistake: Calling launch inside a manual 'new Thread()' or 'ThreadPool', which breaks the ThreadLocal chain."
            );
        }
        return scope;
    }

    // 异常解包工具
    private static void unwrapAndThrow(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException rex) throw rex;
        throw new RuntimeException(cause);
    }

    public static void delay(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted");
        }
    }

    public static class Mutex {
        private final ReentrantLock lock = new ReentrantLock();
        public void withLock(Runnable action) {
            lock.lock(); try { action.run(); } finally { lock.unlock(); }
        }
    }

    @SuppressWarnings("unchecked")
    private static <R> R withScope(StructuredTaskScope<?> scope, Callable<R> block, boolean failOnException) {
        var prev = SCOPE_CONTEXT.get();
        SCOPE_CONTEXT.set((StructuredTaskScope<Object>) scope);
        try (scope) {
            R res = block.call();
            scope.join();
            if (failOnException && scope instanceof StructuredTaskScope.ShutdownOnFailure failScope) {
                failScope.throwIfFailed();
            }
            return res;
        } catch (Exception e) {
            if (e instanceof ExecutionException execEx) unwrapAndThrow(execEx);
            if (e instanceof RuntimeException rex) throw rex;
            throw new RuntimeException(e);
        } finally {
            if (prev != null) SCOPE_CONTEXT.set(prev);
            else SCOPE_CONTEXT.remove();
        }
    }

    private static class ShutdownOnFirst<T> extends StructuredTaskScope<T> {
        private final AtomicReference<Subtask<? extends T>> firstResult = new AtomicReference<>();
        @Override
        protected void handleComplete(Subtask<? extends T> subtask) {
            if (firstResult.compareAndSet(null, subtask)) {
                shutdown();
            }
        }
        public T result() throws ExecutionException {
            Subtask<? extends T> subtask = firstResult.get();
            if (subtask == null) throw new ExecutionException(new NoSuchElementException("No tasks completed"));
            if (subtask.state() == Subtask.State.SUCCESS) return subtask.get();
            else throw new ExecutionException(subtask.exception());
        }
    }
}