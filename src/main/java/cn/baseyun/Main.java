package cn.baseyun;

import static cn.baseyun.Coroutine.*;

public class Main {
    public static void main(String[] args) {

        runBlocking(() -> {
            Job<String> hello = async(() -> {
                delay(1000);
                System.out.println("done");
                return "Hello";
            });

            Job<String> world = async(() -> {
                delay(2000);
                return "World";
            });


            System.out.println(STR."\{hello.await()} \{world.await()}");
        });


    }
}