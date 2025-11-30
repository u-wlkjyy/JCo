# Java Coroutines (JDK 21+)

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Virtual Threads](https://img.shields.io/badge/Virtual-Threads-Enabled-green.svg)](https://openjdk.org/jeps/444)

**JCo** 是一个极简的 Java 并发库，它利用 JDK 21 原生的 **虚拟线程 (Virtual Threads)** 和 **结构化并发 (Structured Concurrency)**，在 Java 中完美复刻了 **Kotlin 协程** 的优雅开发体验。

不需要引入复杂的 Reactive 框架，不需要回调地狱，仅需一个类，即可让你的 Java 代码拥有协程的形与神。

## 核心特性

*   **零依赖**：基于原生 JDK 21，无任何第三方库依赖，极致轻量。
*   **Kotlin 风格 API**：`runBlocking`, `launch`, `async`, `await`, `delay`, `yield`... 保持原汁原味。
*   **线程调度器**：提供 `Dispatchers.Default`（CPU密集型）和 `Dispatchers.Virtual`（IO密集型），支持 `withContext` 和调度器参数切换执行上下文。
*   **智能竞速**：
    *   `race`: 谁快选谁（一完即止，适用于超时控制）。
    *   `raceSuccess`: 谁成选谁（一成即止，适用于高可用调用）。
*   **结构化并发**：自动管理线程生命周期，父协程自动等待子协程，异常自动传播。
*   **集合并发流**：`map`、`forEachParallel`、`awaitAll`，一行代码实现 List 并发处理。
*   **协程锁**：提供 `Mutex`，防止虚拟线程 Pinning 的安全锁。
*   **容错机制**：支持 `supervisorScope`，单任务失败不影响全局。

## 安装 (Installation)

### 方式 1：直接集成 (推荐)
本项目核心仅为一个文件 `Coroutine.java`。
[点击查看源码](src/main/java/cn/baseyun/Coroutine.java)

直接将其复制到你的项目中（例如 `utils` 包下），即可立即使用。


## 快速开始

在使用前，建议添加**静态导入**以获得最佳体验：

```java
import static cn.baseyun.Coroutine.*;
```

### 1. 基础并发 (Async & Await)
像写串行代码一样写并行代码。

```java
runBlocking(() -> {
    // 同时启动两个任务
    Job<String> task1 = async(() -> userService.getUser(1));
    Job<Integer> task2 = async(() -> scoreService.getScore(1));

    // 获取结果 (此处会自动挂起虚拟线程等待，而非阻塞操作系统线程)
    System.out.println("User: " + task1.await() + ", Score: " + task2.await());
});
```

### 2. 集合并发处理 (Map & AwaitAll)
一行代码并发处理整个 List，替代复杂的 `CompletableFuture` 流式处理。

```java
runBlocking(() -> {
    List<Integer> ids = List.of(1, 2, 3, 4, 5);

    // 方式1: 使用 map + awaitAll 获取结果
    List<String> results = map(ids, id -> {
        delay(100); // 模拟耗时
        return "User-" + id;
    }).awaitAll();

    // 方式2: 使用 forEachParallel 仅执行操作（无返回值）
    forEachParallel(ids, id -> {
        delay(100);
        System.out.println("Processing: " + id);
    });

    // 方式3: 使用 awaitAll 等待多个 Job
    Job<String> job1 = async(() -> "Result1");
    Job<String> job2 = async(() -> "Result2");
    List<String> allResults = awaitAll(job1, job2);
});
```

### 3. 两种竞速模式 (Race)

**场景 A：`race` (谁快选谁)**
适用于超时控制或多路复用。只要有一个结束（无论成功失败），立即返回。

```java
runBlocking(() -> {
    try {
        String result = race(
            () -> { delay(2000); return "慢服务"; },
            () -> { delay(100); throw new RuntimeException("快服务崩了"); }
        );
    } catch (Exception e) {
        // 这里会捕获到异常，因为"快服务"先结束了(虽然是失败)
        System.out.println("捕获异常: " + e.getMessage());
    }
});
```

**场景 B：`raceSuccess` (谁成选谁)**
适用于高可用(HA)场景。忽略失败，直到有一个成功。

```java
runBlocking(() -> {
    String result = raceSuccess(
        () -> { delay(100); throw new RuntimeException("节点A挂了"); },
        () -> { delay(500); return "节点B数据"; },
        () -> { delay(2000); return "节点C数据"; }
    );
    // 输出: 节点B数据 (自动忽略了A的错误，且不需要等C)
    System.out.println(result);
});
```

### 4. 超时控制 & 重复执行

```java
runBlocking(() -> {
    // 超时控制
    try {
        String res = withTimeout(1000, () -> {
            delay(2000);
            return "永远拿不到";
        });
    } catch (RuntimeException e) {
        System.out.println("任务超时！");
    }

    // 简单的重复执行
    repeat(10, i -> {
        launch(() -> System.out.println("Task " + i));
    });
});
```

### 5. 线程安全锁 (Mutex)
在虚拟线程中**禁止使用 `synchronized`**，请使用 `Mutex`。

```java
Mutex mutex = new Mutex();
AtomicInteger count = new AtomicInteger(0);

runBlocking(() -> {
    // 启动多个并发任务
    repeat(100, i -> {
        launch(() -> {
            mutex.withLock(() -> {
                count.incrementAndGet(); // 安全操作
            });
        });
    });
});
```

### 6. 线程调度器 (Dispatchers) 与上下文切换

**场景**：当你需要在协程中执行 CPU 密集型计算时，可以使用 `withContext` 切换到专用线程池，避免阻塞虚拟线程调度器。

```java
runBlocking(() -> {
    // 在虚拟线程中执行 IO 操作
    String data = fetchDataFromAPI();
    
    // 切换到 CPU 线程池执行计算密集型任务
    Integer result = withContext(Dispatchers.Default, () -> {
        // 这里运行在固定大小的线程池中
        return heavyComputation(data);
    });
    
    // 自动切回虚拟线程继续执行
    saveResult(result);
});
```

**可用调度器**：
- `Dispatchers.Virtual`：虚拟线程调度器（默认，适合 IO 密集型）
- `Dispatchers.Default`：固定线程池（CPU 核心数+1，适合 CPU 密集型）

**注意**：`withContext` 会挂起当前虚拟线程等待结果，但不会阻塞物理线程。

### 7. 指定调度器启动协程

除了使用 `withContext` 切换上下文，你还可以在启动协程时直接指定调度器：

```java
runBlocking(() -> {
    // 在 CPU 线程池中启动协程
    Job<Integer> cpuTask = async(Dispatchers.Default, () -> {
        // CPU 密集型计算
        return heavyComputation();
    });
    
    // 在虚拟线程中启动（默认）
    Job<String> ioTask = async(Dispatchers.Virtual, () -> {
        // IO 操作
        return fetchData();
    });
    
    // 使用 launch 指定调度器（无返回值）
    launch(Dispatchers.Default, () -> {
        performBackgroundWork();
    });
    
    System.out.println(cpuTask.await() + " " + ioTask.await());
});
```

### 8. 协程控制：yield

使用 `yield()` 让出执行权，允许其他协程运行，同时检查取消状态：

```java
runBlocking(() -> {
    Job<Void> task = async(() -> {
        for (int i = 0; i < 1000; i++) {
            // 执行工作
            doWork(i);
            
            // 定期让出执行权，允许取消
            if (i % 100 == 0) {
                yield(); // 检查是否被取消
            }
        }
        return null;
    });
    
    delay(100);
    task.cancel(); // 取消任务
});
```

## 重要限制 (Limitations)

本库使用 `ThreadLocal` 在虚拟线程间传递 `StructuredTaskScope` 上下文。为了保证上下文不丢失，请务必遵守以下规则：

1.  **必须在 Scope 内使用**：所有 `async`, `launch`, `map` 等方法，必须在 `runBlocking` 或其子作用域内部调用。
2.  **禁止野线程**：不要在 `async` 代码块内部手动创建 `new Thread()` 或提交到普通的 `ThreadPoolExecutor`。
    *   正确：直接写业务逻辑，或者是调用阻塞式 IO（JDBC, HTTP Client）。
    *   错误：`new Thread(() -> launch(...)).start()` -> 这会导致上下文断裂抛出异常。

## 原理

JCo 是对 JDK 21 `java.util.concurrent.StructuredTaskScope` 的高层封装。
*   它利用 **虚拟线程** 实现轻量级并发。
*   它利用 **ThreadLocal** 实现隐式的 Scope 传递（消除方法参数传递）。
*   它利用 **CompletableFuture** 桥接结构化并发与非结构化获取结果的需求。

## License

MIT License. Feel free to use in your projects.