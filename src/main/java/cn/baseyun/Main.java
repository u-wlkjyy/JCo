package cn.baseyun;

import static cn.baseyun.Coroutine.*;

public class Main {
    public static void main(String[] args) {

        // 示例1: runBlocking 无返回值
        runBlocking(() -> {
            Job<String> hello = async(() -> {
                delay(1000);
                return "Hello";
            });

            Job<String> world = async(() -> {
                delay(2000);
                return "World";
            });

            String s = withContext(Dispatchers.Default, () -> {
                return "CPU计算结果";
            });

            System.out.println(s);
            System.out.println(STR."\{hello.await()} \{world.await()}");
        });

        // 示例2: runBlocking 有返回值
        String result = runBlocking(() -> {
            Job<Integer> task1 = async(() -> {
                delay(500);
                return 100;
            });

            Job<Integer> task2 = async(() -> {
                delay(800);
                return 200;
            });

            int sum = task1.await() + task2.await();
            return "计算结果: " + sum;
        });

        System.out.println(result);
    }
}