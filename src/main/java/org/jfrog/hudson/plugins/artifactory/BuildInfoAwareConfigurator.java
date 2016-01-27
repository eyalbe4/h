package org.jfrog.hudson.plugins.artifactory;


import org.jfrog.hudson.plugins.artifactory.config.ArtifactoryServer;
import org.jfrog.hudson.plugins.artifactory.util.IncludesExcludes;

/**
 * Represents a class that can be passed to {@link AbstractBuildInfoDeployer} for build info creation
 *
 * @author Shay Yaakov
 */
public interface BuildInfoAwareConfigurator {

    ArtifactoryServer getArtifactoryServer();

    String getRepositoryKey();

    boolean isIncludeEnvVars();

    IncludesExcludes getEnvVarsPatterns();

    boolean isRunChecks();

    String getViolationRecipients();

    boolean isIncludePublishArtifacts();

    String getScopes();

    boolean isLicenseAutoDiscovery();

    boolean isDiscardOldBuilds();

    boolean isDiscardBuildArtifacts();

    boolean isEnableIssueTrackerIntegration();

    boolean isAggregateBuildIssues();

    String getAggregationBuildStatus();
}
