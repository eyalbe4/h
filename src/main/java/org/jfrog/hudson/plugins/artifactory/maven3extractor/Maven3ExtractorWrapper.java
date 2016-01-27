/*
 * Copyright (C) 2010 JFrog Ltd.
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

package org.jfrog.hudson.plugins.artifactory.maven3extractor;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.FormValidation;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jfrog.hudson.plugins.artifactory.ArtifactoryBuilder;
import org.jfrog.hudson.plugins.artifactory.BuildInfoAwareConfigurator;
import org.jfrog.hudson.plugins.artifactory.DeployerOverrider;
import org.jfrog.hudson.plugins.artifactory.ResolverOverrider;
import org.jfrog.hudson.plugins.artifactory.action.ActionableHelper;
import org.jfrog.hudson.plugins.artifactory.config.ArtifactoryServer;
import org.jfrog.hudson.plugins.artifactory.config.Credentials;
import org.jfrog.hudson.plugins.artifactory.config.ServerDetails;
import org.jfrog.hudson.plugins.artifactory.util.FormValidations;
import org.jfrog.hudson.plugins.artifactory.util.IncludesExcludes;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Freestyle Maven 3 configurator.
 *
 * @author Noam Y. Tenne
 */
public class Maven3ExtractorWrapper extends BuildWrapper
        implements DeployerOverrider, ResolverOverrider, BuildInfoAwareConfigurator {

    /**
     * Repository URL and repository to deploy artifacts to
     */
    private final ServerDetails details;
    private final ServerDetails resolveDetails;
    private final Credentials overridingDeployerCredentials;

    private final Credentials overridingResolverCredentials;
    /**
     * If checked (default) deploy maven artifacts
     */
    private final boolean deployArtifacts;
    private final IncludesExcludes artifactDeploymentPatterns;
    private final boolean filterExcludedArtifactsFromBuild;
    /**
     * Include environment variables in the generated build info
     */
    private final boolean includeEnvVars;

    private final boolean aggregateArtifacts;
    private final boolean copyAggregatedArtifacts;
    private final boolean deployBuildInfo;
    private IncludesExcludes envVarsPatterns;
    private final boolean runChecks;

    private final String violationRecipients;

    private final boolean includePublishArtifacts;

    private final String scopes;

    private boolean licenseAutoDiscovery;
    private boolean disableLicenseAutoDiscovery;
    private final boolean discardOldBuilds;
    private final boolean discardBuildArtifacts;
    private final String matrixParams;
    private final boolean enableIssueTrackerIntegration;
    private boolean aggregateBuildIssues;
    private String aggregationBuildStatus;

    private final boolean resolveArtifacts;

    @DataBoundConstructor
    public Maven3ExtractorWrapper(ServerDetails details, ServerDetails resolveDetails,
            Credentials overridingDeployerCredentials,
            Credentials overridingResolverCredentials, IncludesExcludes artifactDeploymentPatterns,
            boolean deployArtifacts, boolean aggregateArtifacts, boolean copyAggregatedArtifacts, boolean deployBuildInfo,
            boolean includeEnvVars, IncludesExcludes envVarsPatterns,
            boolean runChecks, String violationRecipients, boolean includePublishArtifacts,
            String scopes, boolean disableLicenseAutoDiscovery, boolean discardOldBuilds,
            boolean discardBuildArtifacts, String matrixParams,
            boolean enableIssueTrackerIntegration, boolean aggregateBuildIssues, String aggregationBuildStatus,
            boolean resolveArtifacts,boolean filterExcludedArtifactsFromBuild) {
        this.details = details;
        this.resolveDetails = resolveDetails;
        this.overridingDeployerCredentials = overridingDeployerCredentials;
        this.overridingResolverCredentials = overridingResolverCredentials;
        this.artifactDeploymentPatterns = artifactDeploymentPatterns;
        this.envVarsPatterns = envVarsPatterns;
        this.runChecks = runChecks;
        this.violationRecipients = violationRecipients;
        this.includePublishArtifacts = includePublishArtifacts;
        this.scopes = scopes;
        this.discardOldBuilds = discardOldBuilds;
        this.discardBuildArtifacts = discardBuildArtifacts;
        this.matrixParams = matrixParams;
        this.enableIssueTrackerIntegration = enableIssueTrackerIntegration;
        this.aggregateBuildIssues = aggregateBuildIssues;
        this.aggregationBuildStatus = aggregationBuildStatus;
        this.resolveArtifacts = resolveArtifacts;
        this.licenseAutoDiscovery = !disableLicenseAutoDiscovery;
        this.aggregateArtifacts = aggregateArtifacts;
        this.copyAggregatedArtifacts = copyAggregatedArtifacts;
        this.deployBuildInfo = deployBuildInfo;
        this.deployArtifacts = deployArtifacts;
        this.includeEnvVars = includeEnvVars;
        this.filterExcludedArtifactsFromBuild = filterExcludedArtifactsFromBuild;
    }

    // NOTE: The following getters are used by jelly. Do not remove them

    public ServerDetails getDetails() {
        return details;
    }

    public ServerDetails getResolveDetails() {
        return resolveDetails;
    }

    public boolean isDiscardOldBuilds() {
        return discardOldBuilds;
    }

    public boolean isDiscardBuildArtifacts() {
        return discardBuildArtifacts;
    }

    public boolean isOverridingDefaultDeployer() {
        return (getOverridingDeployerCredentials() != null);
    }

    public Credentials getOverridingDeployerCredentials() {
        return overridingDeployerCredentials;
    }

    public boolean isOverridingDefaultResolver() {
        return getOverridingResolverCredentials() != null;
    }

    public Credentials getOverridingResolverCredentials() {
        return overridingResolverCredentials;
    }

    public boolean isDeployArtifacts() {
        return deployArtifacts;
    }

    public boolean isResolveArtifacts() {
        return resolveArtifacts;
    }

    public String getMatrixParams() {
        return matrixParams;
    }

    public IncludesExcludes getArtifactDeploymentPatterns() {
        return artifactDeploymentPatterns;
    }

    public boolean isDeployBuildInfo() {
        return deployBuildInfo;
    }

    public boolean isAggregateArtifacts () {
        return aggregateArtifacts;
    }

    public boolean isCopyAggregatedArtifacts (){
        return copyAggregatedArtifacts;
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

    public ArtifactoryServer getResolverArtifactoryServer() {
        List<ArtifactoryServer> servers = getDescriptor().getArtifactoryServers();
        for (ArtifactoryServer server : servers) {
            if (server.getName().equals(getResolverArtifactoryName())) {
                return server;
            }
        }
        return null;
    }

    public String getResolverArtifactoryName() {
        return resolveDetails != null ? resolveDetails.artifactoryName : null;
    }

    public String getDownloadRepositoryKey() {
        return resolveDetails != null ? resolveDetails.downloadRepositoryKey : null;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public String getRepositoryKey() {
        return details != null ? details.repositoryKey : null;
    }

    public boolean isIncludePublishArtifacts() {
        return includePublishArtifacts;
    }

    /**
     * @return The snapshots deployment repository. If not defined the releases deployment repository will be returned
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public String getSnapshotsRepositoryKey() {
        return details != null ?
                (details.snapshotsRepositoryKey != null ? details.snapshotsRepositoryKey : details.repositoryKey) :
                null;
    }

    public boolean isLicenseAutoDiscovery() {
        return licenseAutoDiscovery;
    }

    public boolean isDisableLicenseAutoDiscovery() {
        return disableLicenseAutoDiscovery;
    }

    public String getArtifactoryName() {
        return details != null ? details.artifactoryName : null;
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

    public String getViolationRecipients() {
        return violationRecipients;
    }

    public boolean isRunChecks() {
        return runChecks;
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

    public boolean isFilterExcludedArtifactsFromBuild() {
        return filterExcludedArtifactsFromBuild;
    }

    public ArtifactoryServer getArtifactoryServer(String artifactoryServerName) {
        List<ArtifactoryServer> servers = getDescriptor().getArtifactoryServers();
        for (ArtifactoryServer server : servers) {
            if (server.getName().equals(artifactoryServerName)) {
                return server;
            }
        }
        return null;
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject project) {
        return ActionableHelper.getArtifactoryProjectAction(details.getArtifactoryUrl(), project);
    }

    @Override
    public Environment setUp(final AbstractBuild build, Launcher launcher, final BuildListener listener)
            throws IOException, InterruptedException {

        final String artifactoryServerName = getArtifactoryName();
        if (StringUtils.isBlank(artifactoryServerName)) {
            return super.setUp(build, launcher, listener);
        }
        final ArtifactoryServer artifactoryServer = getArtifactoryServer(artifactoryServerName);
        if (artifactoryServer == null) {
            listener.getLogger().format("[JFROG] No Artifactory server configured for %s. " +
                    "Please check your configuration.", artifactoryServerName).println();
            build.setResult(Result.FAILURE);
            throw new IllegalArgumentException("No Artifactory server configured for " + artifactoryServerName);
        }
        build.setResult(Result.SUCCESS);

        final MavenExtractorEnvironment environment = new MavenExtractorEnvironment(build, this, listener);

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener)
                    throws IOException, InterruptedException {
                return environment.tearDown(build, listener);
            }

            @Override
            public void buildEnvVars(Map<String, String> env) {
                environment.buildEnvVars(env);
            }
        };
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension(optional = true)
    public static class DescriptorImpl extends BuildWrapperDescriptor {
        public DescriptorImpl() {
            super(Maven3ExtractorWrapper.class);
            load();
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return item.getClass().isAssignableFrom(FreeStyleProject.class);
        }

        @Override
        public String getDisplayName() {
            return "Maven3-Artifactory Integration";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            req.bindParameters(this, "maven3");
            save();
            return true;
        }

        public FormValidation doCheckViolationRecipients(@QueryParameter String value) {
            return FormValidations.validateEmails(value);
        }

        /**
         * Returns the list of {@link ArtifactoryServer} configured.
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
