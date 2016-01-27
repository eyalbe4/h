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

package org.jfrog.hudson.plugins.artifactory.gradle;

import com.google.common.collect.Iterables;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.plugins.gradle.Gradle;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jfrog.gradle.plugin.artifactory.task.BuildInfoBaseTask;
import org.jfrog.hudson.plugins.artifactory.ArtifactoryBuilder;
import org.jfrog.hudson.plugins.artifactory.BuildInfoAwareConfigurator;
import org.jfrog.hudson.plugins.artifactory.DeployerOverrider;
import org.jfrog.hudson.plugins.artifactory.action.ActionableHelper;
import org.jfrog.hudson.plugins.artifactory.action.BuildInfoResultAction;
import org.jfrog.hudson.plugins.artifactory.config.ArtifactoryServer;
import org.jfrog.hudson.plugins.artifactory.config.Credentials;
import org.jfrog.hudson.plugins.artifactory.config.ServerDetails;
import org.jfrog.hudson.plugins.artifactory.release.UnifiedPromoteBuildAction;
import org.jfrog.hudson.plugins.artifactory.util.ExtractorUtils;
import org.jfrog.hudson.plugins.artifactory.util.FormValidations;
import org.jfrog.hudson.plugins.artifactory.util.IncludesExcludes;
import org.jfrog.hudson.plugins.artifactory.util.PublisherContext;
import org.jfrog.hudson.plugins.artifactory.util.ResolverContext;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;


/**
 * Gradle-Artifactory plugin configuration, allows to add the server details, deployment username/password, as well as
 * flags to deploy ivy, maven, and artifacts, as well as specifications of the location of the remote plugin (.gradle)
 * groovy script.
 *
 * @author Tomer Cohen
 */
public class ArtifactoryGradleConfigurator extends BuildWrapper implements DeployerOverrider,
        BuildInfoAwareConfigurator {
    private final boolean filterExcludedArtifactsFromBuild;
    private ServerDetails details;
    private boolean deployArtifacts;
    private final Credentials overridingDeployerCredentials;
    public final boolean deployMaven;
    public final boolean deployIvy;
    public final String remotePluginLocation;
    private IncludesExcludes envVarsPatterns;
    public final boolean deployBuildInfo;
    public final boolean includeEnvVars;
    private final boolean runChecks;
    private final String violationRecipients;
    private final boolean includePublishArtifacts;
    private final String scopes;
    private final boolean licenseAutoDiscovery;
    private final boolean disableLicenseAutoDiscovery;
    private final String ivyPattern;
    private final boolean enableIssueTrackerIntegration;
    private final boolean aggregateBuildIssues;
    private String aggregationBuildStatus;
    private final String artifactPattern;
    private final boolean notM2Compatible;
    private final IncludesExcludes artifactDeploymentPatterns;
    private final boolean discardOldBuilds;
    private final boolean discardBuildArtifacts;
    private final String matrixParams;
    private final boolean skipInjectInitScript;
    private final boolean allowPromotionOfNonStagedBuilds;

    @DataBoundConstructor
    public ArtifactoryGradleConfigurator(ServerDetails details, Credentials overridingDeployerCredentials,
            boolean deployMaven, boolean deployIvy, boolean deployArtifacts, String remotePluginLocation,
            boolean includeEnvVars, IncludesExcludes envVarsPatterns,
            boolean deployBuildInfo, boolean runChecks, String violationRecipients,
            boolean includePublishArtifacts, String scopes, boolean disableLicenseAutoDiscovery, String ivyPattern,
            String artifactPattern, boolean notM2Compatible, IncludesExcludes artifactDeploymentPatterns,
            boolean discardOldBuilds, boolean discardBuildArtifacts, String matrixParams, boolean skipInjectInitScript,
            boolean enableIssueTrackerIntegration, boolean aggregateBuildIssues, String aggregationBuildStatus,
            boolean allowPromotionOfNonStagedBuilds,boolean filterExcludedArtifactsFromBuild) {
        this.details = details;
        this.overridingDeployerCredentials = overridingDeployerCredentials;
        this.deployMaven = deployMaven;
        this.deployIvy = deployIvy;
        this.deployArtifacts = deployArtifacts;
        this.remotePluginLocation = remotePluginLocation;
        this.includeEnvVars = includeEnvVars;
        this.envVarsPatterns = envVarsPatterns;
        this.deployBuildInfo = deployBuildInfo;
        this.runChecks = runChecks;
        this.violationRecipients = violationRecipients;
        this.includePublishArtifacts = includePublishArtifacts;
        this.scopes = scopes;
        this.disableLicenseAutoDiscovery = disableLicenseAutoDiscovery;
        this.ivyPattern = ivyPattern;
        this.enableIssueTrackerIntegration = enableIssueTrackerIntegration;
        this.aggregateBuildIssues = aggregateBuildIssues;
        this.aggregationBuildStatus = aggregationBuildStatus;
        this.artifactPattern = cleanString(artifactPattern);
        this.notM2Compatible = notM2Compatible;
        this.artifactDeploymentPatterns = artifactDeploymentPatterns;
        this.discardOldBuilds = discardOldBuilds;
        this.discardBuildArtifacts = discardBuildArtifacts;
        this.matrixParams = matrixParams;
        this.skipInjectInitScript = skipInjectInitScript;
        this.licenseAutoDiscovery = !disableLicenseAutoDiscovery;
        this.allowPromotionOfNonStagedBuilds = allowPromotionOfNonStagedBuilds;
        this.filterExcludedArtifactsFromBuild = filterExcludedArtifactsFromBuild;
    }

    public ServerDetails getDetails() {
        return details;
    }

    public String getMatrixParams() {
        return matrixParams;
    }

    public boolean isDiscardBuildArtifacts() {
        return discardBuildArtifacts;
    }

    public boolean isDiscardOldBuilds() {
        return discardOldBuilds;
    }

    public boolean isSkipInjectInitScript() {
        return skipInjectInitScript;
    }

    public boolean isOverridingDefaultDeployer() {
        return (getOverridingDeployerCredentials() != null);
    }

    public Credentials getOverridingDeployerCredentials() {
        return overridingDeployerCredentials;
    }

    public String getViolationRecipients() {
        return violationRecipients;
    }

    public String getArtifactPattern() {
        return cleanString(artifactPattern);
    }

    public String getIvyPattern() {
        return ivyPattern;
    }

    public IncludesExcludes getArtifactDeploymentPatterns() {
        return artifactDeploymentPatterns;
    }

    public boolean isRunChecks() {
        return runChecks;
    }

    public boolean isIncludePublishArtifacts() {
        return includePublishArtifacts;
    }

    public boolean isDeployBuildInfo() {
        return deployBuildInfo;
    }

    public boolean isLicenseAutoDiscovery() {
        return licenseAutoDiscovery;
    }

    public boolean isDisableLicenseAutoDiscovery() {
        return disableLicenseAutoDiscovery;
    }

    public String getScopes() {
        return scopes;
    }

    public boolean isIncludeEnvVars() {
        return includeEnvVars;
    }

    public IncludesExcludes getEnvVarsPatterns() {
        return envVarsPatterns;
    }

    public String getRepositoryKey() {
        return details != null ? details.repositoryKey : null;
    }

    public String getDownloadRepositoryKey() {
        return details != null ? details.downloadRepositoryKey : null;
    }

    public String getArtifactoryName() {
        return details != null ? details.artifactoryName : null;
    }

    public String getArtifactoryUrl() {
        return details != null ? details.getArtifactoryUrl() : null;
    }

    public boolean isDeployArtifacts() {
        return deployArtifacts;
    }

    public boolean isDeployMaven() {
        return deployMaven;
    }

    public boolean isDeployIvy() {
        return deployIvy;
    }

    public boolean isNotM2Compatible() {
        return notM2Compatible;
    }

    public boolean isM2Compatible() {
        return !notM2Compatible;
    }

    public boolean isEnableIssueTrackerIntegration() {
        return enableIssueTrackerIntegration;
    }

    public boolean isAggregateBuildIssues() {
        return aggregateBuildIssues;
    }

    public String getAggregationBuildStatus() {
        return aggregationBuildStatus;
    }

    public boolean isAllowPromotionOfNonStagedBuilds() {
        return allowPromotionOfNonStagedBuilds;
    }

    public boolean isFilterExcludedArtifactsFromBuild() {
        return filterExcludedArtifactsFromBuild;
    }

    private String cleanString(String artifactPattern) {
        return StringUtils.removeEnd(StringUtils.removeStart(artifactPattern, "\""), "\"");
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject project) {
        return ActionableHelper.getArtifactoryProjectAction(details.getArtifactoryUrl(), project);
    }

    @Override
    public Environment setUp(final AbstractBuild build, Launcher launcher, final BuildListener listener)
            throws IOException, InterruptedException {
        ArtifactoryServer artifactoryServer = getArtifactoryServer();
        if (artifactoryServer == null) {
            listener.getLogger().format("[JFROG] No Artifactory server configured for %s. " +
                    "Please check your configuration.", getArtifactoryUrl()).println();
            build.setResult(Result.FAILURE);
            throw new IOException("[JFROG] No Artifactory server configured for " + getArtifactoryUrl() +
                    ". Please check your configuration.");
        }
        String switches = null;
        String originalTasks = null;
        final Gradle gradleBuild = getLastGradleBuild(build.getProject());
        if (gradleBuild != null) {
            switches = gradleBuild.getSwitches() + "";
            if (!skipInjectInitScript) {
                GradleInitScriptWriter writer = new GradleInitScriptWriter(build);
                FilePath workspace = build.getWorkspace();
                FilePath initScript;
                try {
                    initScript =
                            workspace.createTextTempFile("init-artifactory", "gradle", writer.generateInitScript(),
                                    false);
                } catch (Exception e) {
                    listener.getLogger().println("[JFROG] Error occurred while writing Gradle Init Script: " + e.getMessage());
                    build.setResult(Result.FAILURE);
                    return new Environment() {
                    };
                }
                String initScriptPath = initScript.getRemote();
                initScriptPath = initScriptPath.replace('\\', '/');
                setTargetsField(gradleBuild, "switches", switches + " " + "--init-script " + initScriptPath);
            }
            originalTasks = gradleBuild.getTasks() + "";
            final String tasks = gradleBuild.getTasks() + "";
            if (!StringUtils.contains(tasks, BuildInfoBaseTask.BUILD_INFO_TASK_NAME)) {
                setTargetsField(gradleBuild, "tasks", tasks + " " + BuildInfoBaseTask.BUILD_INFO_TASK_NAME);
            }
        } else {
            listener.getLogger().println("[JFROG] [WARN] No Gradle build configured");
        }
        final String finalSwitches = switches;
        final String finalOriginalTasks = originalTasks;
        return new Environment() {
            @Override
            public void buildEnvVars(Map<String, String> env) {
                ServerDetails serverDetails = getDetails();
                PublisherContext publisherContext = new PublisherContext.Builder()
                        .artifactoryServer(getArtifactoryServer()).serverDetails(serverDetails)
                        .deployerOverrider(ArtifactoryGradleConfigurator.this).runChecks(isRunChecks())
                        .includePublishArtifacts(isIncludePublishArtifacts())
                        .violationRecipients(getViolationRecipients()).scopes(getScopes())
                        .licenseAutoDiscovery(isLicenseAutoDiscovery()).discardOldBuilds(isDiscardOldBuilds())
                        .deployArtifacts(isDeployArtifacts()).includesExcludes(getArtifactDeploymentPatterns())
                        .filterExcludedArtifactsFromBuild(isFilterExcludedArtifactsFromBuild())
                        .skipBuildInfoDeploy(!isDeployBuildInfo())
                        .includeEnvVars(isIncludeEnvVars()).envVarsPatterns(getEnvVarsPatterns())
                        .discardBuildArtifacts(isDiscardBuildArtifacts()).matrixParams(getMatrixParams())
                        .artifactsPattern(getArtifactPattern()).ivyPattern(getIvyPattern())
                        .deployIvy(isDeployIvy()).deployMaven(isDeployMaven()).maven2Compatible(isM2Compatible())
                        .enableIssueTrackerIntegration(isEnableIssueTrackerIntegration())
                        .aggregateBuildIssues(isAggregateBuildIssues())
                        .aggregationBuildStatus(getAggregationBuildStatus()).build();

                ResolverContext resolverContext = null;
                if (StringUtils.isNotBlank(serverDetails.downloadRepositoryKey)) {
                    // Resolution server and overriding credentials are currently shared by the deployer and resolver in
                    // the UI. So here we use the same server details and for credentials we try deployer override and
                    // then default resolver
                    Credentials resolverCredentials;
                    if (isOverridingDefaultDeployer()) {
                        resolverCredentials = getOverridingDeployerCredentials();
                    } else {
                        resolverCredentials = getArtifactoryServer().getResolvingCredentials();
                    }
                    resolverContext = new ResolverContext(getArtifactoryServer(), serverDetails, resolverCredentials);
                }

                try {
                    ExtractorUtils.addBuilderInfoArguments(env, build, listener, publisherContext, resolverContext);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {
                boolean success = false;
                if (gradleBuild != null) {
                    // restore the original configuration
                    setTargetsField(gradleBuild, "switches", finalSwitches);
                    setTargetsField(gradleBuild, "tasks", finalOriginalTasks);
                }
                Result result = build.getResult();
                if (result != null && result.isBetterOrEqualTo(Result.SUCCESS)) {
                    if (isDeployBuildInfo()) {
                        build.getActions().add(new BuildInfoResultAction(getArtifactoryUrl(), build));
                        if (isAllowPromotionOfNonStagedBuilds()) {
                            ArtifactoryGradleConfigurator configurator = ActionableHelper.getBuildWrapper(
                                    (BuildableItemWithBuildWrappers) build.getProject(),
                                    ArtifactoryGradleConfigurator.class);
                            if (configurator != null) {
                                build.getActions()
                                        .add(new UnifiedPromoteBuildAction<ArtifactoryGradleConfigurator>(build,
                                                ArtifactoryGradleConfigurator.this));
                            }
                        }
                    }
                    success = true;
                }
                return success;
            }
        };
    }

    private Gradle getLastGradleBuild(AbstractProject project) {
        if (project instanceof Project) {
            List<Gradle> gradles = ActionableHelper.getBuilders((Project) project, Gradle.class);
            return Iterables.getLast(gradles, null);
        }
        return null;
    }

    private void setTargetsField(Gradle builder, String fieldName, String value) {
        try {
            Field targetsField = builder.getClass().getDeclaredField(fieldName);
            targetsField.setAccessible(true);
            targetsField.set(builder, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public ArtifactoryServer getArtifactoryServer() {
        List<ArtifactoryServer> servers = getDescriptor().getArtifactoryServers();
        for (ArtifactoryServer server : servers) {
            if (server.getName().equals(getArtifactoryName())) {
                return server;
            }
        }
        return null;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension(optional = true)
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        public DescriptorImpl() {
            super(ArtifactoryGradleConfigurator.class);
            load();
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return item.getClass().isAssignableFrom(FreeStyleProject.class);
        }

        @Override
        public String getDisplayName() {
            return "Gradle-Artifactory Integration";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            req.bindParameters(this, "gradle");
            save();
            return true;
        }

        public FormValidation doCheckViolationRecipients(@QueryParameter String value) {
            return FormValidations.validateEmails(value);
        }

        /**
         * Returns the list of {@link org.jfrog.hudson.plugins.artifactory.config.ArtifactoryServer} configured.
         *
         * @return can be empty but never null.
         */
        public List<ArtifactoryServer> getArtifactoryServers() {
            ArtifactoryBuilder.DescriptorImpl descriptor = (ArtifactoryBuilder.DescriptorImpl)
                    Hudson.getInstance().getDescriptor(ArtifactoryBuilder.class);
            return descriptor.getArtifactoryServers();
        }

        public boolean isJiraPluginEnabled() {
            return (Hudson.getInstance().getPlugin("jira") != null);
        }
    }
}
