/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.phoneblock.answerbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import de.haumacher.phoneblock.answerbot.DialogueFactory.SynchronizedOutputStream;

/**
 * Tests that {@link SynchronizedOutputStream} serializes concurrent writes.
 */
class TestSynchronizedOutputStream {

	@Test
	void testConcurrentWritesAreSerialized() throws Exception {
		AtomicBoolean inWrite = new AtomicBoolean();
		int[] written = {0};
		OutputStream target = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				enter();
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				enter();
				written[0] += len;
			}

			private void enter() {
				if (!inWrite.compareAndSet(false, true)) {
					throw new AssertionError("Concurrent write to recording.");
				}
				try {
					Thread.sleep(0, 1000);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					inWrite.set(false);
				}
			}
		};

		OutputStream sync = new SynchronizedOutputStream(target);

		int threads = 4;
		int perThread = 2000;
		Thread[] workers = new Thread[threads];
		for (int i = 0; i < threads; i++) {
			workers[i] = new Thread(() -> {
				for (int j = 0; j < perThread; j++) {
					try {
						sync.write(new byte[] {1, 2, 3}, 0, 3);
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				}
			});
			workers[i].start();
		}
		for (Thread worker : workers) {
			worker.join();
		}

		assertEquals(threads * perThread * 3, written[0]);
	}
}
