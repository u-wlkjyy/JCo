package cn.baseyun;

import static cn.baseyun.Coroutine.*;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {

    public static void main(String[] args) {
        runBlocking(() -> {
           launch(() -> {
               delay(1000);
           });
            return null;
        });
        System.out.println("done");
    }

}