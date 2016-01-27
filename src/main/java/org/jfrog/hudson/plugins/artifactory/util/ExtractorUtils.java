/*
 * Copyright (C) 2011 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.hudson.plugins.artifactory.util;

import com.google.common.collect.Lists;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.io.Closeables;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.*;
import hudson.slaves.SlaveComputer;
import hudson.tasks.LogRotator;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.BuildInfoFields;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.ClientProperties;
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.hudson.plugins.artifactory.action.ActionableHelper;
import org.jfrog.hudson.plugins.artifactory.config.ArtifactoryServer;
import org.jfrog.hudson.plugins.artifactory.config.Credentials;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @author Tomer Cohen
 */
public class ExtractorUtils {

    /**
     * Flag to indicate whether an external extractor was used, and the work doesn't need to be done from inside
     * Hudson.
     */
    public static final String EXTRACTOR_USED = "extractor.used";

    private ExtractorUtils() {
        // utility class
        throw new IllegalAccessError();
    }

    /**
     * Get the VCS revision from the Hudson build environment. The search will one of "SVN_REVISION", "GIT_COMMIT",
     * "P4_CHANGELIST" in the environment.
     *
     * @param env The Hudson build environment.
     * @return The vcs revision for supported VCS
     */
    public static String getVcsRevision(Map<String, String> env) {
        String revision = env.get("SVN_REVISION");
        if (StringUtils.isBlank(revision)) {
            revision = env.get("GIT_COMMIT");
        }
        if (StringUtils.isBlank(revision)) {
            revision = env.get("P4_CHANGELIST");
        }
        return revision;
    }

    /**
     * Add build info properties that will be read by an external extractor. All properties are then saved into a {@code
     * buildinfo.properties} into a temporary location. The location is then put into an environment variable {@link
     * org.jfrog.build.api.BuildInfoConfigProperties#PROP_PROPS_FILE} for the extractor to read.
     *
     * @param env              A map of the environment variables that are to be persisted into the buildinfo.properties
     *                         file. NOTE: nothing should be added to the env in this method
     * @param build            The build from which to get build/project related information from (e.g build name and
     *                         build number).
     * @param listener
     * @param publisherContext A context for publisher settings
     */
    public static ArtifactoryClientConfiguration addBuilderInfoArguments(Map<String, String> env, AbstractBuild build,
            BuildListener listener, PublisherContext publisherContext,
            ResolverContext resolverContext)
            throws IOException, InterruptedException {

        EnvVars envVars = new EnvVars();
        for (EnvironmentContributingAction a : Util.filter(build.getActions(), EnvironmentContributingAction.class)) {
            a.buildEnvVars(build, envVars);
        }
        env.putAll(envVars);

        listener.getLogger().println("*** Start env vars ***");
        for (Map.Entry<String, String> entry : env.entrySet()) {
            listener.getLogger().println(entry.getKey() + " = " + entry.getValue());
        }
        listener.getLogger().println("*** End env vars ***");

        ArtifactoryClientConfiguration configuration = new ArtifactoryClientConfiguration(new NullLog());

        if (publisherContext != null) {
            setPublisherInfo(env, build, resolverContext, publisherContext, configuration);
        }

        if (resolverContext != null) {
            setResolverInfo(configuration, resolverContext);
        }

        if ((Hudson.getInstance().getPlugin("jira") != null) && (publisherContext != null) &&
                publisherContext.isEnableIssueTrackerIntegration()) {
            new IssuesTrackerHelper(build, listener, publisherContext.isAggregateBuildIssues(),
                    publisherContext.getAggregationBuildStatus()).setIssueTrackerInfo(configuration);
        }

        IncludesExcludes envVarsPatterns = new IncludesExcludes("", "");
        if (publisherContext != null && publisherContext.getEnvVarsPatterns() != null) {
            envVarsPatterns = publisherContext.getEnvVarsPatterns();
        }
        addEnvVars(env, build, configuration, envVarsPatterns);
        persistConfiguration(build, configuration, env, listener);
        return configuration;
    }

    private static void setResolverInfo(ArtifactoryClientConfiguration configuration, ResolverContext context) {
        configuration.setTimeout(context.getServer().getTimeout());
        configuration.resolver.setContextUrl(context.getServer().getUrl());
        configuration.resolver.setRepoKey(context.getServerDetails().downloadRepositoryKey);
        configuration.resolver.setUsername(context.getCredentials().getUsername());
        configuration.resolver.setPassword(context.getCredentials().getPassword());
    }

    /**
     * Set all the parameters relevant for publishing artifacts and build info
     */
    private static void setPublisherInfo(Map<String, String> env, AbstractBuild build, ResolverContext resolverContext,
            PublisherContext publisherContext, ArtifactoryClientConfiguration configuration) {
        configuration.setActivateRecorder(Boolean.TRUE);

        String buildName = sanitizeBuildName(build.getProject().getFullName());
        configuration.info.setBuildName(buildName);
        configuration.publisher.addMatrixParam("build.name", buildName);
        String buildNumber = build.getNumber() + "";
        configuration.info.setBuildNumber(buildNumber);
        configuration.publisher.addMatrixParam("build.number", buildNumber);

        Date buildStartDate = build.getTimestamp().getTime();
        configuration.info.setBuildStarted(buildStartDate.getTime());
        configuration.info.setBuildTimestamp(String.valueOf(buildStartDate.getTime()));
        configuration.publisher.addMatrixParam("build.timestamp", String.valueOf(buildStartDate.getTime()));

        String vcsRevision = getVcsRevision(env);
        if (StringUtils.isNotBlank(vcsRevision)) {
            configuration.info.setVcsRevision(vcsRevision);
            configuration.publisher.addMatrixParam(BuildInfoFields.VCS_REVISION, vcsRevision);
        }

        if (StringUtils.isNotBlank(publisherContext.getArtifactsPattern())) {
            configuration.publisher.setIvyArtifactPattern(publisherContext.getArtifactsPattern());
        }
        if (StringUtils.isNotBlank(publisherContext.getIvyPattern())) {
            configuration.publisher.setIvyPattern(publisherContext.getIvyPattern());
        }
        configuration.publisher.setM2Compatible(publisherContext.isMaven2Compatible());
        String buildUrl = ActionableHelper.getBuildUrl(build);
        if (StringUtils.isNotBlank(buildUrl)) {
            configuration.info.setBuildUrl(buildUrl);
        }

        String userName = null;
        Cause.UpstreamCause parent = ActionableHelper.getUpstreamCause(build);
        if (parent != null) {
            String parentProject = sanitizeBuildName(parent.getUpstreamProject());
            configuration.info.setParentBuildName(parentProject);
            configuration.publisher.addMatrixParam(BuildInfoFields.BUILD_PARENT_NAME, parentProject);
            String parentBuildNumber = parent.getUpstreamBuild() + "";
            configuration.info.setParentBuildNumber(parentBuildNumber);
            configuration.publisher.addMatrixParam(BuildInfoFields.BUILD_PARENT_NUMBER, parentBuildNumber);
            userName = "auto";
        }

        userName = ActionableHelper.getUserCausePrincipal(build, userName);

        configuration.info.setPrincipal(userName);
        configuration.info.setAgentName("Hudson");
        configuration.info.setAgentVersion(build.getHudsonVersion());
        ArtifactoryServer publishingServer = publisherContext.getArtifactoryServer();
        Credentials preferredDeployer =
                CredentialResolver.getPreferredDeployer(publisherContext.getDeployerOverrider(), publishingServer);
        if (StringUtils.isNotBlank(preferredDeployer.getUsername())) {
            configuration.publisher.setUsername(preferredDeployer.getUsername());
            configuration.publisher.setPassword(preferredDeployer.getPassword());
        }

        configuration.setTimeout(publishingServer.getTimeout());
        configuration.publisher.setContextUrl(publishingServer.getUrl());
        configuration.publisher.setRepoKey(publisherContext.getServerDetails().repositoryKey);
        configuration.publisher.setSnapshotRepoKey(publisherContext.getServerDetails().snapshotsRepositoryKey);

        configuration.info.licenseControl.setRunChecks(publisherContext.isRunChecks());
        configuration.info.licenseControl.setIncludePublishedArtifacts(publisherContext.isIncludePublishArtifacts());
        configuration.info.licenseControl.setAutoDiscover(publisherContext.isLicenseAutoDiscovery());
        configuration.publisher.setCopyAggregatedArtifacts( publisherContext.isCopyAggregatedArtifacts());
        configuration.publisher.setPublishAggregatedArtifacts( publisherContext.isPublishAggregatedArtifacts());
        if (StringUtils.isNotBlank(publisherContext.getAggregateArtifactsPath())) {
            configuration.publisher.setAggregateArtifacts( publisherContext.getAggregateArtifactsPath());
        }
        if (publisherContext.isRunChecks()) {
            if (StringUtils.isNotBlank(publisherContext.getViolationRecipients())) {
                configuration.info.licenseControl.setViolationRecipients(publisherContext.getViolationRecipients());
            }
            if (StringUtils.isNotBlank(publisherContext.getScopes())) {
                configuration.info.licenseControl.setScopes(publisherContext.getScopes());
            }
        }
        if (publisherContext.isDiscardOldBuilds()) {
            LogRotator rotator = build.getProject().getLogRotator();
            if (rotator != null) {
                if (rotator.getNumToKeep() > -1) {
                    configuration.info.setBuildRetentionDays(rotator.getNumToKeep());
                }
                if (rotator.getDaysToKeep() > -1) {
                    configuration.info.setBuildRetentionMinimumDate(String.valueOf(rotator.getDaysToKeep()));
                }
                configuration.info.setDeleteBuildArtifacts(publisherContext.isDiscardBuildArtifacts());
            }
            configuration.info.setBuildNumbersNotToDelete(getBuildNumbersNotToBeDeletedAsString(build));
        }
        configuration.publisher.setPublishArtifacts(publisherContext.isDeployArtifacts());
        configuration.publisher.setEvenUnstable(publisherContext.isEvenIfUnstable());
        configuration.publisher.setIvy(publisherContext.isDeployIvy());
        configuration.publisher.setMaven(publisherContext.isDeployMaven());
        IncludesExcludes deploymentPatterns = publisherContext.getIncludesExcludes();
        if (deploymentPatterns != null) {
            String includePatterns = deploymentPatterns.getIncludePatterns();
            if (StringUtils.isNotBlank(includePatterns)) {
                configuration.publisher.setIncludePatterns(includePatterns);
            }
            String excludePatterns = deploymentPatterns.getExcludePatterns();
            if (StringUtils.isNotBlank(excludePatterns)) {
                configuration.publisher.setExcludePatterns(excludePatterns);
            }
        }
        configuration.publisher.setFilterExcludedArtifactsFromBuild(publisherContext.isFilterExcludedArtifactsFromBuild());
        configuration.publisher.setPublishBuildInfo(!publisherContext.isSkipBuildInfoDeploy());
        configuration.setIncludeEnvVars(publisherContext.isIncludeEnvVars());
        IncludesExcludes envVarsPatterns = publisherContext.getEnvVarsPatterns();
        if (envVarsPatterns != null) {
            configuration.setEnvVarsIncludePatterns(envVarsPatterns.getIncludePatterns());
            configuration.setEnvVarsExcludePatterns(envVarsPatterns.getExcludePatterns());
        }
        addMatrixParams(publisherContext, configuration.publisher, env);
    }

    /**
     * Replaces occurrences of '/' with ' :: ' if exist
     */
    public static String sanitizeBuildName(String buildName) {
        return StringUtils.replace(buildName, "/", " :: ");
    }

    /**
     * Get the list of build numbers that are to be kept forever.
     */
    public static List<String> getBuildNumbersNotToBeDeleted(AbstractBuild build) {
        List<String> notToDelete = Lists.newArrayList();
        List<? extends Run<?, ?>> builds = build.getProject().getBuilds();
        for (Run<?, ?> run : builds) {
            if (run.isKeepLog()) {
                notToDelete.add(String.valueOf(run.getNumber()));
            }
        }
        return notToDelete;
    }

    private static String getBuildNumbersNotToBeDeletedAsString(AbstractBuild build) {
        StringBuilder builder = new StringBuilder();
        List<String> notToBeDeleted = getBuildNumbersNotToBeDeleted(build);
        for (String notToDelete : notToBeDeleted) {
            builder.append(notToDelete).append(",");
        }
        return builder.toString();
    }

    public static void persistConfiguration(AbstractBuild build, ArtifactoryClientConfiguration configuration,
            Map<String, String> env, BuildListener listener) throws IOException, InterruptedException {
        FilePath propertiesFile = build.getWorkspace().createTextTempFile("buildInfo", ".properties", "", false);
        configuration.setPropertiesFile(propertiesFile.getRemote());

        listener.getLogger().println("*** Adding env var: BUILDINFO_PROPFILE=" + propertiesFile.getRemote());
        listener.getLogger().println("*** Adding env var: " + BuildInfoConfigProperties.PROP_PROPS_FILE + "=" + propertiesFile.getRemote());

        env.put("BUILDINFO_PROPFILE", propertiesFile.getRemote());
        env.put(BuildInfoConfigProperties.PROP_PROPS_FILE, propertiesFile.getRemote());

        listener.getLogger().println("*** Persisting properties file.");

        if (!(Computer.currentComputer() instanceof SlaveComputer)) {
            configuration.persistToPropertiesFile();
        } else {
            try {
                Properties properties = new Properties();
                properties.putAll(configuration.getAllRootConfig());
                properties.putAll(configuration.getAllProperties());
                File tempFile = File.createTempFile("buildInfo", ".properties");
                FileOutputStream stream = new FileOutputStream(tempFile);
                try {
                    properties.store(stream, "");
                } finally {
                    Closeables.closeQuietly(stream);
                }
                propertiesFile.copyFrom(tempFile.toURI().toURL());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void addMatrixParams(PublisherContext context,
            ArtifactoryClientConfiguration.PublisherHandler publisher,
            Map<String, String> env) {
        String matrixParams = context.getMatrixParams();
        if (StringUtils.isBlank(matrixParams)) {
            return;
        }
        String[] keyValuePairs = StringUtils.split(matrixParams, "; ");
        if (keyValuePairs == null) {
            return;
        }
        for (String keyValuePair : keyValuePairs) {
            String[] split = StringUtils.split(keyValuePair, "=");
            if (split.length == 2) {
                String value = Util.replaceMacro(split[1], env);
                publisher.addMatrixParam(split[0], value);
            }
        }
    }

    private static void addEnvVars(Map<String, String> env, AbstractBuild<?, ?> build,
            ArtifactoryClientConfiguration configuration, IncludesExcludes envVarsPatterns) {
        IncludeExcludePatterns patterns = new IncludeExcludePatterns(envVarsPatterns.getIncludePatterns(),
                envVarsPatterns.getExcludePatterns());

        // Add only the Hudson specific environment variables
        MapDifference<String, String> envDifference = Maps.difference(env, System.getenv());
        Map<String, String> filteredEnvDifference = envDifference.entriesOnlyOnLeft();
        configuration.info.addBuildVariables(filteredEnvDifference, patterns);

        // Add Hudson build variables
        Map<String, String> buildVariables = build.getBuildVariables();
        MapDifference<String, String> buildVarDifference = Maps.difference(buildVariables, System.getenv());
        Map<String, String> filteredBuildVarDifferences = buildVarDifference.entriesOnlyOnLeft();
        configuration.info.addBuildVariables(filteredBuildVarDifferences, patterns);

        // Write all the deploy (matrix params) properties.
        configuration.fillFromProperties(buildVariables, patterns);
        for (Map.Entry<String, String> entry : buildVariables.entrySet()) {
            if (entry.getKey().startsWith(ClientProperties.PROP_DEPLOY_PARAM_PROP_PREFIX)) {
                configuration.publisher.addMatrixParam(entry.getKey(), entry.getValue());
            }
        }
    }
}
