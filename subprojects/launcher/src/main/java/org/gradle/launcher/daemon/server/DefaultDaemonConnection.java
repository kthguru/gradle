/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.launcher.daemon.server;

import org.gradle.internal.CompositeStoppable;
import org.gradle.internal.Stoppable;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.StoppableExecutor;
import org.gradle.launcher.daemon.protocol.*;
import org.gradle.launcher.daemon.server.exec.DaemonConnection;
import org.gradle.launcher.daemon.server.exec.StdinHandler;
import org.gradle.logging.internal.OutputEvent;
import org.gradle.messaging.remote.internal.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DefaultDaemonConnection implements DaemonConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDaemonConnection.class);
    private final Connection<Object> connection;
    private final StoppableExecutor executor;
    private final StdinQueue stdinQueue;
    private final DisconnectQueue disconnectQueue;

    public DefaultDaemonConnection(final Connection<Object> connection, ExecutorFactory executorFactory) {
        this.connection = connection;
        stdinQueue = new StdinQueue(executorFactory);
        disconnectQueue = new DisconnectQueue();
        executor = executorFactory.create("Handler for " + connection.toString());
        executor.execute(new Runnable() {
            public void run() {
                while (true) {
                    Object message;
                    try {
                        message = connection.receive();
                    } catch (Exception e) {
                        LOGGER.warn("Could not receive message from client.", e);
                        return;
                    }
                    if (message == null) {
                        LOGGER.debug("Received end-of-input from client.");
                        stdinQueue.disconnect();
                        disconnectQueue.disconnect();
                        return;
                    }

                    if (!(message instanceof IoCommand)) {
                        LOGGER.warn("Received unexpected message from client: {}", message);
                        continue;
                    }

                    LOGGER.debug("Received IO message from client: {}", message);
                    stdinQueue.add((IoCommand) message);
                }
            }
        });
    }

    public void onStdin(StdinHandler handler) {
        stdinQueue.useHandler(handler);
    }

    public void onDisconnect(Runnable handler) {
        disconnectQueue.useHandler(handler);
    }

    public void daemonUnavailable(DaemonUnavailable unavailable) {
        connection.dispatch(unavailable);
    }

    public void buildStarted(BuildStarted buildStarted) {
        connection.dispatch(buildStarted);
    }

    public void logEvent(OutputEvent logEvent) {
        connection.dispatch(logEvent);
    }

    public void completed(Result result) {
        connection.dispatch(result);
    }

    public void stop() {
        // 1. Stop handling disconnects. Blocks until the handler has finished.
        // 2. Stop the connection. This means that the thread receiving from the connection will receive a null and finish up.
        // 3. Stop receiving incoming messages. Blocks until the receive thread has finished. This will notify the stdin queue to signal end of input.
        // 4. Stop handling stdin. Blocks until the handler has finished. Discards any queued input.
        CompositeStoppable.stoppable(disconnectQueue, connection, executor, stdinQueue).stop();
    }

    private static class StdinQueue implements Stoppable {
        private final Lock lock = new ReentrantLock();
        private final Condition condition = lock.newCondition();
        private final LinkedList<IoCommand> stdin = new LinkedList<IoCommand>();
        private StoppableExecutor executor;
        private boolean removed;
        private final ExecutorFactory executorFactory;

        private StdinQueue(ExecutorFactory executorFactory) {
            this.executorFactory = executorFactory;
        }

        public void stop() {
            StoppableExecutor executor;
            lock.lock();
            try {
                executor = this.executor;
            } finally {
                lock.unlock();
            }
            if (executor != null) {
                executor.stop();
            }
        }

        public void add(IoCommand command) {
            lock.lock();
            try {
                stdin.add(command);
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        public void useHandler(final StdinHandler handler) {
            if (handler != null) {
                startConsuming(handler);
            } else {
                stopConsuming();
            }
        }

        private void stopConsuming() {
            StoppableExecutor executor;
            lock.lock();
            try {
                stdin.clear();
                removed = true;
                condition.signalAll();
                executor = this.executor;
            } finally {
                lock.unlock();
            }
            if (executor != null) {
                executor.stop();
            }
        }

        private void startConsuming(final StdinHandler handler) {
            lock.lock();
            try {
                if (executor != null) {
                    throw new UnsupportedOperationException("Multiple stdin handlers not supported.");
                }
                executor = executorFactory.create("Stdin handler");
                executor.execute(new Runnable() {
                    public void run() {
                        while (true) {
                            IoCommand command;
                            lock.lock();
                            try {
                                while (!removed && stdin.isEmpty()) {
                                    try {
                                        condition.await();
                                    } catch (InterruptedException e) {
                                        throw UncheckedException.throwAsUncheckedException(e);
                                    }
                                }
                                if (removed) {
                                    return;
                                }
                                command = stdin.removeFirst();
                            } finally {
                                lock.unlock();
                            }
                            try {
                                if (command instanceof CloseInput) {
                                    handler.onEndOfInput();
                                    return;
                                } else {
                                    handler.onInput((ForwardInput) command);
                                }
                            } catch (Exception e) {
                                LOGGER.warn("Could not forward client stdin.", e);
                                return;
                            }
                        }
                    }
                });
            } finally {
                lock.unlock();
            }
        }

        public void disconnect() {
            lock.lock();
            try {
                stdin.clear();
                stdin.add(new CloseInput("<disconnected>"));
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private static class DisconnectQueue implements Stoppable {
        private final Lock lock = new ReentrantLock();
        private final Condition condition = lock.newCondition();
        private Runnable handler;
        private boolean notifying;
        private boolean disconnected;

        public void disconnect() {
            Runnable action;
            lock.lock();
            try {
                disconnected = true;
                if (handler == null) {
                    return;
                }
                action = handler;
                notifying = true;
            } finally {
                lock.unlock();
            }
            runAction(action);
        }

        private void runAction(Runnable action) {
            try {
                action.run();
            } catch (Exception e) {
                LOGGER.warn("Failed to notify disconnect handler.", e);
            } finally {
                lock.lock();
                try {
                    notifying = false;
                    condition.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }

        public void stop() {
            useHandler(null);
        }

        public void useHandler(Runnable handler) {
            if (handler != null) {
                startMonitoring(handler);
            } else {
                stopMonitoring();
            }
        }

        private void startMonitoring(Runnable handler) {
            Runnable action;

            lock.lock();
            try {
                if (this.handler != null) {
                    throw new UnsupportedOperationException("Multiple disconnect handlers not supported.");
                }
                this.handler = handler;
                if (!disconnected) {
                    return;
                }
                action = handler;
                notifying = true;
            } finally {
                lock.unlock();
            }

            runAction(action);
        }

        private void stopMonitoring() {
            lock.lock();
            try {
                while (notifying) {
                    try {
                        condition.await();
                    } catch (InterruptedException e) {
                        throw UncheckedException.throwAsUncheckedException(e);
                    }
                }
                handler = null;
            } finally {
                lock.unlock();
            }
        }
    }
}
