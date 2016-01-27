package org.jfrog.hudson.plugins.artifactory.release;

/**
 * @author Mark Pope
 */
public final class PromotionConfig {

    private final String targetStatus;
    private final String repositoryKey;
    private final String comment;
    private final String ciUser;
    private final boolean useCopy;
    private final boolean includeDependencies;

    public PromotionConfig(String targetStatus, String repositoryKey, String comment, String ciUser, boolean useCopy, boolean includeDependencies) {
        this.targetStatus = targetStatus;
        this.repositoryKey = repositoryKey;
        this.comment = comment;
        this.ciUser = ciUser;
        this.useCopy = useCopy;
        this.includeDependencies = includeDependencies;
    }

    public String getTargetStatus() {
        return targetStatus;
    }

    public String getRepositoryKey() {
        return repositoryKey;
    }

    public String getComment() {
        return comment;
    }

    public String getCiUser() {
        return ciUser;
    }

    public boolean isUseCopy() {
        return useCopy;
    }

    public boolean isIncludeDependencies() {
        return includeDependencies;
    }

}
