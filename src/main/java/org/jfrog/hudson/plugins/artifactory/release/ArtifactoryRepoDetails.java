package org.jfrog.hudson.plugins.artifactory.release;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Mark Pope
 */
public class ArtifactoryRepoDetails {

    private final String repoKey;
    private final String repoName;
    private final String repoUrl;

    @DataBoundConstructor
    public ArtifactoryRepoDetails(String repoUrl, String repoName, String repoKey){
        this.repoName = repoName;
        this.repoUrl = repoUrl;
        this.repoKey = repoKey;
    }

    public String getRepoKey() {
        return repoKey;
    }

    public String getRepoName() {
        return repoName;
    }
    public String getRepoUrl() {
        return repoUrl;
    }

}
