package cn.baseyun;

import static cn.baseyun.Coroutine.*;

public class Main {
    public static void main(String[] args) {

        runBlocking(() -> {
            Job<String> hello = async(() -> {
                delay(1000);
                System.out.println(Thread.currentThread().threadId());
                return "Hello";
            });

            Job<String> world = async(() -> {
                delay(2000);
//                System.out.println(Thread.currentThread().getName());
                System.out.println(Thread.currentThread().threadId());
                return "World";
            });

            String s = withContext(Dispatchers.Default, () -> {
                System.out.println(Thread.currentThread().threadId());
                return "123";
            });

            System.out.println(s);


            System.out.println(STR."\{hello.await()} \{world.await()}");
        });


    }
}