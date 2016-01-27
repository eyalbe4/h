package org.jfrog.hudson.plugins.artifactory;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Cause;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.Agent;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildAgent;
import org.jfrog.build.api.BuildInfoProperties;
import org.jfrog.build.api.BuildRetention;
import org.jfrog.build.api.BuildType;
import org.jfrog.build.api.LicenseControl;
import org.jfrog.build.api.builder.BuildInfoBuilder;
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.build.extractor.clientConfiguration.PatternMatcher;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.hudson.plugins.artifactory.action.ActionableHelper;
import org.jfrog.hudson.plugins.artifactory.util.BuildRetentionFactory;
import org.jfrog.hudson.plugins.artifactory.util.ExtractorUtils;
import org.jfrog.hudson.plugins.artifactory.util.IncludesExcludes;

import java.io.IOException;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;

/**
 * Handles build info creation and deployment
 *
 * @author Shay Yaakov
 */
public class AbstractBuildInfoDeployer {
    private BuildInfoAwareConfigurator configurator;
    protected AbstractBuild build;
    protected BuildListener listener;
    protected ArtifactoryBuildInfoClient client;
    private EnvVars env;

    public AbstractBuildInfoDeployer(BuildInfoAwareConfigurator configurator, AbstractBuild build,
            BuildListener listener, ArtifactoryBuildInfoClient client) throws IOException, InterruptedException {
        this.configurator = configurator;
        this.build = build;
        this.listener = listener;
        this.client = client;
        this.env = build.getEnvironment(listener);
    }

    protected Build createBuildInfo(String buildAgentName, String buildAgentVersion, BuildType buildType) {
        BuildInfoBuilder builder = new BuildInfoBuilder(
                ExtractorUtils.sanitizeBuildName(build.getParent().getFullName()))
                .number(build.getNumber() + "").type(buildType)
                .buildAgent(new BuildAgent(buildAgentName, buildAgentVersion))
                .agent(new Agent("hudson", build.getHudsonVersion()));
        String buildUrl = ActionableHelper.getBuildUrl(build);
        if (StringUtils.isNotBlank(buildUrl)) {
            builder.url(buildUrl);
        }

        Calendar startedTimestamp = build.getTimestamp();
        builder.startedDate(startedTimestamp.getTime());

        long duration = System.currentTimeMillis() - startedTimestamp.getTimeInMillis();
        builder.durationMillis(duration);

        String artifactoryPrincipal = configurator.getArtifactoryServer().getResolvingCredentials().getUsername();
        if (StringUtils.isBlank(artifactoryPrincipal)) {
            artifactoryPrincipal = "";
        }
        builder.artifactoryPrincipal(artifactoryPrincipal);

        String userCause = ActionableHelper.getUserCausePrincipal(build);
        if (userCause != null) {
            builder.principal(userCause);
        }

        Cause.UpstreamCause parent = ActionableHelper.getUpstreamCause(build);
        if (parent != null) {
            String parentProject = ExtractorUtils.sanitizeBuildName(parent.getUpstreamProject());
            int parentNumber = parent.getUpstreamBuild();
            builder.parentName(parentProject);
            builder.parentNumber(parentNumber + "");
            if (StringUtils.isBlank(userCause)) {
                builder.principal("auto");
            }
        }

        String revision = ExtractorUtils.getVcsRevision(env);
        if (StringUtils.isNotBlank(revision)) {
            builder.vcsRevision(revision);
        }

        addBuildInfoProperties(builder);

        LicenseControl licenseControl = new LicenseControl(configurator.isRunChecks());
        if (configurator.isRunChecks()) {
            if (StringUtils.isNotBlank(configurator.getViolationRecipients())) {
                licenseControl.setLicenseViolationsRecipientsList(configurator.getViolationRecipients());
            }
            if (StringUtils.isNotBlank(configurator.getScopes())) {
                licenseControl.setScopesList(configurator.getScopes());
            }
        }
        licenseControl.setIncludePublishedArtifacts(configurator.isIncludePublishArtifacts());
        licenseControl.setAutoDiscover(configurator.isLicenseAutoDiscovery());
        builder.licenseControl(licenseControl);
        BuildRetention buildRetention = new BuildRetention(configurator.isDiscardBuildArtifacts());
        if (configurator.isDiscardOldBuilds()) {
            buildRetention = BuildRetentionFactory.createBuildRetention(build, configurator.isDiscardBuildArtifacts());
        }
        builder.buildRetention(buildRetention);

        Build buildInfo = builder.build();
        // for backwards compatibility for Artifactory 2.2.3
        if (parent != null) {
            buildInfo.setParentBuildId(parent.getUpstreamProject());
        }

        return buildInfo;
    }

    private void addBuildInfoProperties(BuildInfoBuilder builder) {
        if (configurator.isIncludeEnvVars()) {
            IncludesExcludes envVarsPatterns = configurator.getEnvVarsPatterns();
            if (envVarsPatterns != null) {
                IncludeExcludePatterns patterns = new IncludeExcludePatterns(envVarsPatterns.getIncludePatterns(),
                        envVarsPatterns.getExcludePatterns());
                // First add all build related variables
                addBuildVariables(builder, patterns);

                // Then add env variables
                addEnvVariables(builder, patterns);

                // And finally add system variables
                addSystemVariables(builder, patterns);
            }
        }
    }

    private void addBuildVariables(BuildInfoBuilder builder, IncludeExcludePatterns patterns) {
        Map<String, String> buildVariables = build.getBuildVariables();
        for (Map.Entry<String, String> entry : buildVariables.entrySet()) {
            String varKey = entry.getKey();
            if (PatternMatcher.pathConflicts(varKey, patterns)) {
                continue;
            }
            builder.addProperty(BuildInfoProperties.BUILD_INFO_ENVIRONMENT_PREFIX + varKey, entry.getValue());
        }
    }

    private void addEnvVariables(BuildInfoBuilder builder, IncludeExcludePatterns patterns) {
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String varKey = entry.getKey();
            if (PatternMatcher.pathConflicts(varKey, patterns)) {
                continue;
            }
            builder.addProperty(BuildInfoProperties.BUILD_INFO_ENVIRONMENT_PREFIX + varKey, entry.getValue());
        }
    }

    private void addSystemVariables(BuildInfoBuilder builder, IncludeExcludePatterns patterns) {
        Properties systemProperties = System.getProperties();
        Enumeration<?> enumeration = systemProperties.propertyNames();
        while (enumeration.hasMoreElements()) {
            String propertyKey = (String) enumeration.nextElement();
            if (PatternMatcher.pathConflicts(propertyKey, patterns)) {
                continue;
            }
            builder.addProperty(propertyKey, systemProperties.getProperty(propertyKey));
        }
    }
}