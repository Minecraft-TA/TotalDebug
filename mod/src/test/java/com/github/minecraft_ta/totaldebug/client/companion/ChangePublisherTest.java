package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangePublisherTest {
    @Test
    void aStateLeftAndReturnedToBetweenChecksIsToldWhenPublishedAtOnce() {
        AtomicReference<String> state = new AtomicReference<>("server");
        List<String> told = new ArrayList<>();
        ChangePublisher<String> publisher = new ChangePublisher<>(state::get, told::add);
        publisher.republish();
        publisher.tick();

        publisher.publish("menu");
        publisher.publish("menu");
        for (int tick = 0; tick < 20; tick++) publisher.tick();

        assertEquals(List.of("server", "menu", "server"), told, "the rejoin is told although the checks saw no change");
    }
}
