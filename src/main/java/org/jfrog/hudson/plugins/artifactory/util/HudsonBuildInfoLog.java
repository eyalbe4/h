package org.jfrog.hudson.plugins.artifactory.util;

import hudson.model.BuildListener;
import org.jfrog.build.api.util.Log;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wrapper for Hudson build logger, records log messages from BuildInfo
 *
 * @author Shay Yaakov
 */
public class HudsonBuildInfoLog implements Log {
    private static final Logger logger = Logger.getLogger(HudsonBuildInfoLog.class.getName());

    private BuildListener listener;

    public HudsonBuildInfoLog(BuildListener listener) {
        this.listener = listener;
    }

    public void debug(String message) {
        logger.finest(message);
    }

    public void info(String message) {
        listener.getLogger().println(message);
        logger.info(message);
    }

    public void warn(String message) {
        listener.getLogger().println(message);
        logger.warning(message);
    }

    public void error(String message) {
        listener.getLogger().println(message);
        logger.severe(message);
    }

    public void error(String message, Throwable e) {
        listener.getLogger().println(message);
        logger.log(Level.SEVERE, message, e);
    }
}
