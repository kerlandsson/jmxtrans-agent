/*
 * Copyright (c) 2010-2013 the original author or authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */
package org.jmxtrans.agent;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import javax.management.MBeanServer;

import org.jmxtrans.agent.util.logging.Logger;

/**
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public class JmxTransExporter implements ConfigurationChangedListener {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private ThreadFactory threadFactory = new ThreadFactory() {
        final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = Executors.defaultThreadFactory().newThread(r);
            thread.setDaemon(true);
            thread.setName("jmxtrans-agent-" + counter.incrementAndGet());
            return thread;
        }
    };
    private ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1, threadFactory);
    private MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
    private ScheduledFuture scheduledFuture;
    private JmxTransExporterConfiguration config;
    private volatile JmxTransExporterConfiguration newConfiguration;

    public JmxTransExporter(JmxTransExporterConfiguration config) {
        this.config = config;
    }

    public void start() {
        if (logger.isLoggable(Level.FINER)) {
            logger.fine("starting " + this.toString() + " ...");
        } else {
            logger.fine("starting " + getClass().getName() + " ...");
        }

        if (scheduledFuture != null) {
            throw new IllegalArgumentException("Exporter is already started");
        }

        if (config.getResultNameStrategy() == null)
            throw new IllegalStateException("resultNameStrategy is not defined, jmxTransExporter is not properly initialised");

        scheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                collectAndExport();
            }
        }, config.getCollectInterval() / 2, config.getCollectInterval(), config.getCollectIntervalTimeUnit());

        logger.fine(getClass().getName() + " started");
    }

    public void stop() {
        // cancel jobs
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            scheduledFuture = null;
        }
        scheduledExecutorService.shutdown();

        // one last export
        collectAndExport();

        // wait for stop
        try {
            scheduledExecutorService.awaitTermination(config.getCollectInterval(), config.getCollectIntervalTimeUnit());
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        logger.info(getClass().getName() + " stopped.");

    }

    protected void collectAndExport() {
        replaceConfigurationIfChanged();
        OutputWriter outputWriter = config.getOutputWriter();
        try {
            outputWriter.preCollect();
            for (Invocation invocation : config.getInvocations()) {
                try {
                    invocation.invoke(mbeanServer, outputWriter);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Ignore exception invoking " + invocation, e);
                }
            }
            for (Query query : config.getQueries()) {
                try {
                    query.collectAndExport(mbeanServer, outputWriter);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Ignore exception collecting metrics for " + query, e);
                }
            }
            outputWriter.postCollect();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ignore exception flushing metrics ", e);
        }
    }

    private void replaceConfigurationIfChanged() {
        if (newConfiguration != null) {
            logger.log(Level.INFO, "Configuration has changed, destroying the old configuration and replacing with the new");
            config.destroy();
            config = newConfiguration;
            newConfiguration = null;
        }
    }

    @Override
    public String toString() {
        return "JmxTransExporter{" +
                ", configuration=" + config +
                '}';
    }

    @Override
    public void configurationChanged(JmxTransExporterConfiguration configuration) {
        this.newConfiguration = configuration;
    }
}
