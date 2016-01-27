package org.jfrog.hudson.plugins.artifactory.release;

import hudson.model.AbstractBuild;
import hudson.model.Hudson;
import hudson.model.TaskListener;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.jfrog.build.api.builder.PromotionBuilder;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.hudson.plugins.artifactory.UserPluginInfo;
import org.jfrog.hudson.plugins.artifactory.config.ArtifactoryServer;
import org.jfrog.hudson.plugins.artifactory.config.Credentials;
import org.jfrog.hudson.plugins.artifactory.maven3extractor.config.PluginSettings;
import org.jfrog.hudson.plugins.artifactory.util.ExtractorUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Mark Pope
 */
public class ArtifactoryPromoter {

    private final AbstractBuild build;
    private final PluginSettings promotionPlugin;
    private final PromotionConfig promotionConfig;
    private final ArtifactoryServer artifactoryServer;
    private final Credentials deployer;

    public ArtifactoryPromoter(AbstractBuild build, PluginSettings promotionPlugin, PromotionConfig promotionConfig,
                               ArtifactoryServer artifactoryServer, Credentials deployer) {
        this.build = build;
        this.promotionPlugin = promotionPlugin;
        this.promotionConfig = promotionConfig;
        this.artifactoryServer = artifactoryServer;
        this.deployer = deployer;
    }

    public boolean handlePromotion(TaskListener listener) throws IOException {
        ArtifactoryBuildInfoClient client = null;
        try {
            client = artifactoryServer.createArtifactoryClient(deployer.getUsername(), deployer.getPassword(),
                    artifactoryServer.createProxyConfiguration(Hudson.getInstance().proxy));

            if (promotionPlugin != null && !UserPluginInfo.NO_PLUGIN_KEY.equals(promotionPlugin.getPluginName())) {
                return handlePluginPromotion(listener, client);
            } else {
                return handleStandardPromotion(listener, client);
            }
        } finally {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    private boolean handlePluginPromotion(TaskListener listener, ArtifactoryBuildInfoClient client) throws IOException {
        String buildName = ExtractorUtils.sanitizeBuildName(build.getParent().getFullName());
        String buildNumber = build.getNumber() + "";
        HttpResponse pluginPromotionResponse = client.executePromotionUserPlugin(
                promotionPlugin.getPluginName(), buildName, buildNumber, promotionPlugin.getParamMap());
        if (checkSuccess(pluginPromotionResponse, false, false, listener)) {
            listener.getLogger().println("[JFROG] Promotion completed successfully!");
        return true;
        } else {
        listener.getLogger().println("[JFROG] Promotion failed!");
        return false;
        }
    }

    private boolean handleStandardPromotion(TaskListener listener, ArtifactoryBuildInfoClient client) throws IOException {
        // do a dry run first
        PromotionBuilder promotionBuilder = new PromotionBuilder()
                .status(promotionConfig.getTargetStatus())
                .comment(promotionConfig.getComment())
                .ciUser(promotionConfig.getCiUser())
                .targetRepo(promotionConfig.getRepositoryKey())
                .dependencies(promotionConfig.isIncludeDependencies())
                .copy(promotionConfig.isUseCopy())
                .dryRun(true);
        listener.getLogger()
                .println("[JFROG] Performing dry run promotion (no changes are made during dry run) ...");
        String buildName = ExtractorUtils.sanitizeBuildName(build.getParent().getFullName());
        String buildNumber = build.getNumber() + "";
        HttpResponse dryResponse = client.stageBuild(buildName, buildNumber, promotionBuilder.build());
        if (checkSuccess(dryResponse, true, true, listener)) {
            listener.getLogger().println("[JFROG] Dry run finished successfully.\nPerforming promotion ...");
            HttpResponse wetResponse = client.stageBuild(buildName,
                    buildNumber, promotionBuilder.dryRun(false).build());
            if (checkSuccess(wetResponse, false, true, listener)) {
                listener.getLogger().println("[JFROG] Promotion completed successfully!");
            return true;
            }
        }
        listener.getLogger().println("[JFROG] Dry Promotion failed, canceling Promotion!");
        return false;
    }

    /**
     * Checks the status and return true on success
     *
     * @param response
     * @param dryRun
     * @param parseMessages
     * @param listener
     * @return
     */
    private boolean checkSuccess(HttpResponse response, boolean dryRun, boolean parseMessages,
                                 TaskListener listener) {
        StatusLine status = response.getStatusLine();
        try {
            String content = entityToString(response);
            if (assertResponseStatus(dryRun, listener, status, content)) {
                if (parseMessages) {
                    JSONObject json = JSONObject.fromObject(content);
                    JSONArray messages = json.getJSONArray("messages");
                    for (Object messageObj : messages) {
                        JSONObject messageJson = (JSONObject) messageObj;
                        String level = messageJson.getString("level");
                        String message = messageJson.getString("message");
                        // TODO: we don't want to fail if no items were moved/copied. find a way to support it
                        if ((level.equals("WARNING") || level.equals("ERROR")) &&
                                !message.startsWith("No items were")) {
                            listener.error("Received " + level + ": " + message);
                            return false;
                        }
                    }
                }
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed parsing promotion response:"));
        }
        return false;
    }

    private boolean assertResponseStatus(boolean dryRun, TaskListener listener, StatusLine status, String content) {
        if (status.getStatusCode() != 200) {
            if (dryRun) {
                listener.error(
                        "Promotion failed during dry run (no change in Artifactory was done): " + status +
                                "\n" + content);
            } else {
                listener.error(
                        "Promotion failed. View Artifactory logs for more details: " + status + "\n" + content);
            }
            return false;
        }
        return true;
    }

    private String entityToString(HttpResponse response) throws IOException {
        HttpEntity entity = response.getEntity();
        InputStream is = entity.getContent();
        return IOUtils.toString(is, "UTF-8");
    }

}