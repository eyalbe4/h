package org.jfrog.hudson.plugins.artifactory.release;

import com.google.common.collect.Lists;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import org.jfrog.hudson.plugins.artifactory.ArtifactoryBuilder;
import org.jfrog.hudson.plugins.artifactory.action.ActionableHelper;
import org.jfrog.hudson.plugins.artifactory.config.ArtifactoryServer;
import org.jfrog.hudson.plugins.artifactory.config.Credentials;
import org.jfrog.hudson.plugins.artifactory.gradle.ArtifactoryGradleConfigurator;
import org.jfrog.hudson.plugins.artifactory.util.CredentialResolver;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.List;

/**
 * @author Mark Pope
 */
public class AutoPromotePostBuildAction extends Notifier {

    private String targetStatus;
    private String comment;
    private boolean includeDependencies;
    private boolean useCopy;
    private ArtifactoryRepoDetails details;

    @DataBoundConstructor
    public AutoPromotePostBuildAction(String targetStatus, String comment, boolean includeDependencies, boolean useCopy, ArtifactoryRepoDetails details) {
        this.targetStatus = targetStatus;
        this.comment = comment;
        this.includeDependencies = includeDependencies;
        this.useCopy = useCopy;
        this.details = details;
    }

    public ArtifactoryRepoDetails getDetails() {
        return details;
    }

    public String getTargetStatus() {
        return targetStatus;
    }

    public String getComment() {
        return comment;
    }

    public boolean isIncludeDependencies() {
        return includeDependencies;
    }

    public boolean isUseCopy() {
        return useCopy;
    }


    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {

        if (build.getResult() == Result.SUCCESS) {
            ArtifactoryServer artifactoryServer = getArtifactoryServer(build.getProject());
            Credentials deployer = getDeployerCredentials(artifactoryServer);
            String ciUser = getCiUser();
            String repositoryKey = details.getRepoKey();

            PromotionConfig promotionConfig = new PromotionConfig(targetStatus, repositoryKey, comment, ciUser, useCopy, includeDependencies);
            ArtifactoryPromoter promoter = new ArtifactoryPromoter(build, null, promotionConfig, artifactoryServer, deployer);
            try {
                return promoter.handlePromotion(listener);
            } catch (Exception e) {
                e.printStackTrace(listener.error(e.getMessage()));
                return false;
            }
        }
        listener.getLogger().println("Not auto promoting. Build status was " + build.getResult() + ". Auto promotion requires SUCCESS.");
        return false;
    }

    private Credentials getDeployerCredentials(ArtifactoryServer artifactoryServer) {
        return CredentialResolver.getPreferredDeployer(artifactoryServer);
    }

    private ArtifactoryServer getArtifactoryServer(AbstractProject project) {
        ArtifactoryGradleConfigurator configurator = ActionableHelper.getBuildWrapper((BuildableItemWithBuildWrappers) project, ArtifactoryGradleConfigurator.class);
        return configurator.getArtifactoryServer();
    }

    private String getCiUser() {
        User user = User.current();
        return (user == null) ? "anonymous" : user.getId();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher>  {

        @Override
        public String getDisplayName() {
            return "Auto promote successful builds";
        }

        @Override
        public boolean isApplicable(Class arg0) {
            return true;
        }

        public List<String> getTargetStatuses() {
            return Lists.newArrayList("Released");
        }

        public List<ArtifactoryServer> getArtifactoryServers() {
            ArtifactoryBuilder.DescriptorImpl descriptor = (ArtifactoryBuilder.DescriptorImpl)
                    Hudson.getInstance().getDescriptor(ArtifactoryBuilder.class);
            return descriptor.getArtifactoryServers();
        }

    }
}
