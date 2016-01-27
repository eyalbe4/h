/*
 * Copyright (C) 2011 JFrog Ltd.
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

package org.jfrog.hudson.plugins.artifactory.release;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import hudson.model.*;
import hudson.security.ACL;
import hudson.security.Permission;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jfrog.hudson.plugins.artifactory.ArtifactoryPlugin;
import org.jfrog.hudson.plugins.artifactory.BuildInfoAwareConfigurator;
import org.jfrog.hudson.plugins.artifactory.DeployerOverrider;
import org.jfrog.hudson.plugins.artifactory.UserPluginInfo;
import org.jfrog.hudson.plugins.artifactory.config.ArtifactoryServer;
import org.jfrog.hudson.plugins.artifactory.config.Credentials;
import org.jfrog.hudson.plugins.artifactory.maven3extractor.config.PluginSettings;
import org.jfrog.hudson.plugins.artifactory.util.CredentialResolver;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * This badge action is added to a successful staged builds. It allows performing additional promotion.
 *
 * @author Noam Y. Tenne
 */
public class UnifiedPromoteBuildAction<C extends BuildInfoAwareConfigurator & DeployerOverrider> extends TaskAction
        implements BuildBadgeAction {
    private final AbstractBuild build;
    private final C configurator;

    private String targetStatus;
    private String repositoryKey;
    private String comment;
    private boolean useCopy;
    private boolean includeDependencies;
    private PluginSettings promotionPlugin;

    public UnifiedPromoteBuildAction(AbstractBuild build, C configurator) {
        this.build = build;
        this.configurator = configurator;
    }

    @Override
    protected Permission getPermission() {
        return ArtifactoryPlugin.PROMOTE;
    }

    public String getIconFileName() {
        return "/plugin/artifactory/images/artifactory-release.png";
    }

    public String getDisplayName() {
        return "Artifactory Release Promotion";
    }

    public String getUrlName() {
        if (hasPromotionPermission()) {
            return "promote";
        }
        // return null to hide this action
        return null;
    }

    public boolean hasPromotionPermission() {
        return getACL().hasPermission(getPermission());
    }

    public AbstractBuild getBuild() {
        return build;
    }

    public void setTargetStatus(String targetStatus) {
        this.targetStatus = targetStatus;
    }

    public void setRepositoryKey(String repositoryKey) {
        this.repositoryKey = repositoryKey;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public void setUseCopy(boolean useCopy) {
        this.useCopy = useCopy;
    }

    public void setIncludeDependencies(boolean includeDependencies) {
        this.includeDependencies = includeDependencies;
    }

    public String getPromotionPluginName() {
        return (promotionPlugin != null) ? promotionPlugin.getPluginName() : null;
    }

    public void setPromotionPlugin(PluginSettings promotionPlugin) {
        this.promotionPlugin = promotionPlugin;
    }

    public String getPluginParamValue(String pluginName, String paramKey) {
        return (promotionPlugin != null) ? promotionPlugin.getPluginParamValue(pluginName, paramKey) : null;
    }

    /**
     * @return List of target repositories for deployment (release repositories first). Called from the UI.
     */
    public List<String> getRepositoryKeys() {
        ArtifactoryServer artifactoryServer = configurator.getArtifactoryServer();
        if (artifactoryServer == null) {
            return Lists.newArrayList();
        }
        List<String> repos = artifactoryServer.getReleaseRepositoryKeysFirst();
        repos.add(0, "");  // option not to move
        return repos;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    public List<String> getTargetStatuses() {
        return Lists.newArrayList(/*"Staged", */"Released", "Rolled-back");
    }

    /**
     * @return The repository selected by the latest promotion (to be selected by default).
     */
    public String lastPromotionRepository() {
        // TODO: implement
        return null;
    }

    /**
     * Select which view to display based on the state of the promotion. Will return the form if user selects to perform
     * promotion. Progress will be returned if the promotion is currently in progress.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public void doIndex(StaplerRequest req, StaplerResponse resp) throws IOException, ServletException {
        req.getView(this, chooseAction()).forward(req, resp);
    }

    /**
     * Form submission is calling this method
     */
    @SuppressWarnings({"UnusedDeclaration"})
    public void doSubmit(StaplerRequest req, StaplerResponse resp) throws IOException, ServletException {
        getACL().checkPermission(getPermission());

        req.bindParameters(this);

        // current user is bound to the thread and will be lost in the perform method
        User user = User.current();
        String ciUser = (user == null) ? "anonymous" : user.getId();

        JSONObject formData = req.getSubmittedForm();
        if (formData.has("promotionPlugin")) {
            JSONObject pluginSettings = formData.getJSONObject("promotionPlugin");
            if (pluginSettings.has("pluginName")) {
                String pluginName = pluginSettings.getString("pluginName");
                if (!UserPluginInfo.NO_PLUGIN_KEY.equals(pluginName)) {
                    PluginSettings settings = new PluginSettings();
                    Map<String, String> paramMap = Maps.newHashMap();
                    settings.setPluginName(pluginName);
                    Map<String, Object> filteredPluginSettings = Maps.filterKeys(pluginSettings,
                            new Predicate<String>() {
                                public boolean apply(String input) {
                                    return StringUtils.isNotBlank(input) && !"pluginName".equals(input);
                                }
                            });
                    for (Map.Entry<String, Object> settingsEntry : filteredPluginSettings.entrySet()) {
                        String key = settingsEntry.getKey();
                        paramMap.put(key, pluginSettings.getString(key));
                    }
                    paramMap.put("ciUser", ciUser);
                    if (!paramMap.isEmpty()) {
                        settings.setParamMap(paramMap);
                    }
                    setPromotionPlugin(settings);
                }
            }
        }

        ArtifactoryServer server = configurator.getArtifactoryServer();

        new PromoteWorkerThread(server, CredentialResolver.getPreferredDeployer(configurator, server), ciUser).start();

        resp.sendRedirect(".");
    }

    public List<UserPluginInfo> getPromotionsUserPluginInfo() {
        ArtifactoryServer artifactoryServer = configurator.getArtifactoryServer();
        if (artifactoryServer == null) {
            return Lists.newArrayList(UserPluginInfo.NO_PLUGIN);
        }
        return artifactoryServer.getPromotionsUserPluginInfo();
    }

    @Override
    protected ACL getACL() {
        return build.getACL();
    }

    private synchronized String chooseAction() {
        return workerThread == null ? "form.jelly" : "progress.jelly";
    }

    /**
     * The thread that performs the promotion asynchronously.
     */
    public final class PromoteWorkerThread extends TaskThread {

        private final ArtifactoryServer artifactoryServer;
        private final Credentials deployer;
        private final PromotionConfig promotionConfig;

        public PromoteWorkerThread(ArtifactoryServer artifactoryServer, Credentials deployer, String ciUser) {
            super(UnifiedPromoteBuildAction.this, ListenerAndText.forMemory(null));
            this.artifactoryServer = artifactoryServer;
            this.deployer = deployer;
            this.promotionConfig = new PromotionConfig(targetStatus, repositoryKey, comment, ciUser, useCopy, includeDependencies);
        }

        @Override
        protected void perform(TaskListener listener) {
            ArtifactoryPromoter promoter = new ArtifactoryPromoter(build, promotionPlugin, promotionConfig, artifactoryServer, deployer);
            try {
                long started = System.currentTimeMillis();
                listener.getLogger().println("[JFROG] Promoting build ....");

                boolean result = promoter.handlePromotion(listener);
                if (result) {
                    listener.getLogger().println("Promoting build .... success!");
                } else {
                    listener.getLogger().println("Promoting build .... failed!");
                }

                build.save();
                // if the client gets back to the progress (after the redirect) page when this thread already done,
                // she will get an error message because the log dies with the thread. So lets delay up to 3 seconds
                long timeToWait = 2000 - (System.currentTimeMillis() - started);
                if (timeToWait > 0) {
                    Thread.sleep(timeToWait);
                }
                workerThread = null;
            } catch (Throwable e) {
                e.printStackTrace(listener.error(e.getMessage()));
            }
        }


    }
}
