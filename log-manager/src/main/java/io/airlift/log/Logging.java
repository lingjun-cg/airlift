/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.log;

import com.google.common.collect.ImmutableSortedMap;
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;

import javax.annotation.concurrent.GuardedBy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.LogManager;

import static com.google.common.collect.Maps.fromProperties;

/**
 * Initializes the logging subsystem.
 * <p>
 * java.util.Logging, System.out and System.err are tunneled through the logging system.
 * <p>
 * System.out and System.err are assigned to loggers named "stdout" and "stderr", respectively.
 */
public class Logging
        implements Resource
{
    private static final Logger log = Logger.get(Logging.class);
    private static final String ROOT_LOGGER_NAME = "";
    private static final java.util.logging.Logger ROOT = java.util.logging.Logger.getLogger("");
    private static Logging instance;

    // hard reference to loggers for which we set the level
    @GuardedBy("this")
    private final Map<String, java.util.logging.Logger> loggers = new HashMap<>();

    @GuardedBy("this")
    private OutputStreamHandler consoleHandler;

    private List<LogFileConfigForCR> logConfigForCR = new ArrayList<>();
    private List<RollingFileHandler> fileHandlerForCR = new ArrayList<>();

    /**
     * Sets up default logging:
     * <p>
     * - INFO level
     * - Log entries are written to stderr
     *
     * @return the logging system singleton
     */
    public static synchronized Logging initialize()
    {
        if (instance == null) {
            instance = new Logging();
            Core.getGlobalContext().register(instance);
        }

        return instance;
    }

    private Logging()
    {
        ROOT.setLevel(Level.INFO.toJulLevel());
        for (Handler handler : ROOT.getHandlers()) {
            ROOT.removeHandler(handler);
        }

        rewireStdStreams();
    }

    private void rewireStdStreams()
    {
        logConsole(new NonCloseableOutputStream(System.err));
        log.info("Logging to stderr");

        redirectStdStreams();
    }

    private static void redirectStdStreams()
    {
        System.setOut(new PrintStream(new LoggingOutputStream(Logger.get("stdout")), true));
        System.setErr(new PrintStream(new LoggingOutputStream(Logger.get("stderr")), true));
    }

    private synchronized void logConsole(OutputStream stream)
    {
        consoleHandler = new OutputStreamHandler(stream);
        ROOT.addHandler(consoleHandler);
    }

    public synchronized void disableConsole()
    {
        log.info("Disabling stderr output");
        ROOT.removeHandler(consoleHandler);
        consoleHandler = null;
    }

    public void logToFile(String logPath, int maxHistory, long maxSizeInBytes)
    {
        log.info("Logging to %s", logPath);

        RollingFileHandler rollingFileHandler = new RollingFileHandler(logPath, maxHistory, maxSizeInBytes);
        ROOT.addHandler(rollingFileHandler);
        logConfigForCR.add(new LogFileConfigForCR(logPath, maxHistory, maxSizeInBytes));
        fileHandlerForCR.add(rollingFileHandler);
    }

    public Level getRootLevel()
    {
        return getLevel(ROOT_LOGGER_NAME);
    }

    public void setRootLevel(Level newLevel)
    {
        setLevel(ROOT_LOGGER_NAME, newLevel);
    }

    public void setLevels(File file)
            throws IOException
    {
        Properties properties = new Properties();
        try (InputStream inputStream = new FileInputStream(file)) {
            properties.load(inputStream);
        }

        fromProperties(properties).forEach((loggerName, value) ->
                setLevel(loggerName, Level.valueOf(value.toUpperCase(Locale.US))));
    }

    public Level getLevel(String loggerName)
    {
        return getEffectiveLevel(java.util.logging.Logger.getLogger(loggerName));
    }

    private static Level getEffectiveLevel(java.util.logging.Logger logger)
    {
        java.util.logging.Level level = logger.getLevel();
        if (level == null) {
            java.util.logging.Logger parent = logger.getParent();
            if (parent != null) {
                return getEffectiveLevel(parent);
            }
        }
        if (level == null) {
            return Level.OFF;
        }
        return Level.fromJulLevel(level);
    }

    public synchronized void clearLevel(String loggerName)
    {
        java.util.logging.Logger logger = loggers.remove(loggerName);
        if (logger != null) {
            logger.setLevel(null);
        }
    }

    public synchronized void setLevel(String loggerName, Level level)
    {
        loggers.computeIfAbsent(loggerName, java.util.logging.Logger::getLogger)
                .setLevel(level.toJulLevel());
    }

    public Map<String, Level> getAllLevels()
    {
        ImmutableSortedMap.Builder<String, Level> levels = ImmutableSortedMap.naturalOrder();
        for (String loggerName : Collections.list(LogManager.getLogManager().getLoggerNames())) {
            java.util.logging.Level level = java.util.logging.Logger.getLogger(loggerName).getLevel();
            if (level != null) {
                levels.put(loggerName, Level.fromJulLevel(level));
            }
        }
        return levels.build();
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception
    {
        if (fileHandlerForCR.isEmpty()) {
            return;
        }
        fileHandlerForCR.forEach((h) -> h.close());
        fileHandlerForCR.clear();
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception
    {
        if (logConfigForCR.isEmpty()) {
            return;
        }
        for (LogFileConfigForCR config : logConfigForCR) {
            RollingFileHandler rollingFileHandler = new RollingFileHandler(config.logPath, config.maxHistory, config.maxSizeInBytes);
            ROOT.addHandler(rollingFileHandler);
        }
        logConfigForCR.clear();
    }

    public void configure(LoggingConfiguration config)
    {
        if (config.getLogPath() != null) {
            logToFile(config.getLogPath(), config.getMaxHistory(), config.getMaxSize().toBytes());
        }

        if (!config.isConsoleEnabled()) {
            disableConsole();
        }

        if (config.getLevelsFile() != null) {
            try {
                setLevels(new File(config.getLevelsFile()));
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static class LogFileConfigForCR
    {
        private String logPath;
        private int maxHistory;
        private long maxSizeInBytes;

        public LogFileConfigForCR(String logPath, int maxHistory, long maxSizeInBytes)
        {
            this.logPath = logPath;
            this.maxHistory = maxHistory;
            this.maxSizeInBytes = maxSizeInBytes;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            LogFileConfigForCR that = (LogFileConfigForCR) o;
            return maxHistory == that.maxHistory && maxSizeInBytes == that.maxSizeInBytes && Objects.equals(logPath, that.logPath);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(logPath, maxHistory, maxSizeInBytes);
        }
    }
}
