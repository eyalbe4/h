// CHECKSTYLE:OFF

package org.jfrog.hudson.plugins.artifactory;

import org.jvnet.localizer.Localizable;
import org.jvnet.localizer.ResourceBundleHolder;

@SuppressWarnings({
    "",
    "PMD"
})
public class Messages {

    private final static ResourceBundleHolder holder = ResourceBundleHolder.get(Messages.class);

    /**
     * Allows the user to promote a build
     * 
     */
    public static String permission_promote() {
        return holder.format("permission.promote");
    }

    /**
     * Allows the user to promote a build
     * 
     */
    public static Localizable _permission_promote() {
        return new Localizable(holder, "permission.promote");
    }

    /**
     * Allows the user to run release builds
     * 
     */
    public static String permission_release() {
        return holder.format("permission.release");
    }

    /**
     * Allows the user to run release builds
     * 
     */
    public static Localizable _permission_release() {
        return new Localizable(holder, "permission.release");
    }

    /**
     * Artifactory
     * 
     */
    public static String permission_group() {
        return holder.format("permission.group");
    }

    /**
     * Artifactory
     * 
     */
    public static Localizable _permission_group() {
        return new Localizable(holder, "permission.group");
    }

}
