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

package org.jfrog.hudson.plugins.artifactory.maven3extractor;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.Result;
import hudson.remoting.Which;
import hudson.scm.NullChangeLogParser;
import hudson.scm.NullSCM;
import hudson.tasks.Builder;
import org.apache.commons.lang.StringUtils;
import org.eclipse.hudson.api.model.IBaseBuildableProject;
import org.hudsonci.maven.plugin.builder.MavenBuilder;
import org.hudsonci.maven.plugin.builder.internal.MavenInstallationValidator;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.maven.BuildInfoRecorder;
import org.jfrog.hudson.plugins.artifactory.action.ActionableHelper;
import org.jfrog.hudson.plugins.artifactory.action.BuildInfoResultAction;
import org.jfrog.hudson.plugins.artifactory.config.Credentials;
import org.jfrog.hudson.plugins.artifactory.config.ServerDetails;
import org.jfrog.hudson.plugins.artifactory.util.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class for setting up the {@link hudson.model.Environment} for a {@link AbstractBuild} build. Responsible for adding the new
 * maven opts with the location of the plugin.
 *
 * @author Tomer Cohen
 */
public class MavenExtractorEnvironment extends Environment {
    public static final String MAVEN_PLUGIN_OPTS = "-Dm3plugin.lib";
    public static final String CLASSWORLDS_CONF_KEY = "classworlds.conf";

    private FilePath classworldsConf;
    private String propertiesFilePath;

    // the build env vars method may be called again from another setUp of a wrapper so we need this flag to
    // attempt only once certain operations (like copying file or changing maven opts).
    private boolean initialized;
    private AbstractBuild build;
    private Maven3ExtractorWrapper wrapper;
    private BuildListener buildListener;

    private       int                          buildStepCounter = -1;
    private final List<? extends Builder>      builders;
    private final List<? extends MavenBuilder> mavenBuilders;
    private final String[]                     originalMavenOpts;
    private final String                       aggregateArtifactsPath;


    @SuppressWarnings({ "AssignmentToNull" , "SuppressionAnnotation" })
    public MavenExtractorEnvironment(AbstractBuild build, Maven3ExtractorWrapper wrapper, BuildListener buildListener)
        throws IOException, InterruptedException
    {
        this.build                  = build;
        this.wrapper                = wrapper;
        this.buildListener          = buildListener;
        this.builders               = ActionableHelper.getBuilders(( IBaseBuildableProject ) build.getProject(), Builder.class );
        this.mavenBuilders          = enabledMavenBuilders( builders );
        this.originalMavenOpts      = new String[ builders.size() ];
        this.aggregateArtifactsPath = ( wrapper.isAggregateArtifacts()) && ( mavenBuilders.size() > 1 ) ?
                                        aggregateDirectory().getRemote() : null;

        buildListener.getLogger().println("[JFROG] Created an Artifactory Maven Extractor Environment with " + builders.size()
                + " build steps and " + mavenBuilders.size()
                + " Maven build steps and aggregated here: '" + aggregateArtifactsPath + "'");
    }


    private List<MavenBuilder> enabledMavenBuilders ( List<? extends Builder> builders )
    {
        List<MavenBuilder> result = new ArrayList<MavenBuilder>( builders.size());

        for ( Builder builder : builders ) {
            if ( isEnabledMavenBuilder( builder )){ result.add(( MavenBuilder ) builder ); }
        }

        return result;
    }

    private boolean isEnabledMavenBuilder( Builder builder ) {

        if ( ! ( builder instanceof MavenBuilder )){ return false; }

        MavenBuilder mavenBuilder = ( MavenBuilder ) builder;

        return ( mavenBuilder.getConfig()                == null ) ||
                ( mavenBuilder.getConfig().getMavenOpts() == null ) ||
                ( ! (mavenBuilder.getConfig().getMavenOpts().contains( "-Dartifactory.plugin.skip" ) ||
                        mavenBuilder.getConfig().getProperties().contains("artifactory.plugin.skip"))
                );
    }


    @Override
    public void buildEnvVars( Map<String, String> env )
    {
        if (build.getWorkspace() == null) {
            // HAP-274 - workspace might not be initialized yet (this method will be called later in the build lifecycle)
            return;
        }

        buildStepCounter++; // Starts with zero and goes 0, 1, 2, ..

        if(builders.size() <= buildStepCounter){ return; }

        Builder builder = builders.get( buildStepCounter );

        if ( ! isEnabledMavenBuilder( builder )){ return; }

        final MavenBuilder mavenBuilder       = ( MavenBuilder ) builder;
        final String       mavenOpts          = mavenBuilder.getConfig().getMavenOpts();
        originalMavenOpts[ buildStepCounter ] = mavenOpts;
        boolean isLastEnabledMavenBuilder     = ( builder == mavenBuilders.get( mavenBuilders.size() - 1 ));

        //If an SCM is configured
        if (!initialized && !(build.getProject().getScm() instanceof NullSCM)) {
            //Handle all the extractor info only when a checkout was already done
            boolean checkoutWasPerformed = true;
            try {
                Field scmField = AbstractBuild.class.getDeclaredField("scm");
                scmField.setAccessible(true);
                Object scmObject = scmField.get(build);
                //Null changelog parser is set when a checkout wasn't performed yet
                checkoutWasPerformed = !(scmObject instanceof NullChangeLogParser);
            } catch (Exception e) {
                buildListener.getLogger().println("[JFROG] [WARN] An error occurred while testing if the SCM checkout " +
                        "has already been performed: " + e.getMessage());
            }
            if (!checkoutWasPerformed) {
                return;
            }
        }

        // if not valid Maven version don't modify the environment
        if (!isMavenVersionValid()) {
            return;
        }
        env.put(ExtractorUtils.EXTRACTOR_USED, "true");

        if (classworldsConf == null && !env.containsKey(CLASSWORLDS_CONF_KEY)) {
            classworldsConf = copyFile("classworlds-freestyle.conf", "classworlds", ".conf");
        }

        if (classworldsConf != null) {
            env.put(CLASSWORLDS_CONF_KEY, classworldsConf.getRemote());
        }

        if (!env.containsKey(MavenInstallationValidator.MAVEN_EXEC_WIN)) {
            FilePath mavenExecBat = copyFile("mvn.bat", "mvn", ".bat");
            FilePath mavenExecSh = copyFile("mvn", "mvn", "");
            if (mavenExecBat != null && mavenExecSh != null) {
                env.put(MavenInstallationValidator.MAVEN_EXEC_NIX, mavenExecSh.getRemote());
                env.put(MavenInstallationValidator.MAVEN_EXEC_WIN, mavenExecBat.getRemote());
            }
        }

        try {
            if (!Boolean.toString(false).equals(System.getenv("ART_APPEND_MAVEN_OPTS"))) {
                mavenBuilder.getConfig().setMavenOpts( appendNewMavenOpts( mavenOpts ));
            }

            PublisherContext publisherContext = null;
            if (wrapper != null) {
                publisherContext = createPublisherContext(wrapper, isLastEnabledMavenBuilder);
            }

            ResolverContext resolverContext = null;
            if (wrapper != null && wrapper.isResolveArtifacts()) {
                Credentials resolverCredentials = CredentialResolver.getPreferredResolver(
                        wrapper, wrapper.getResolverArtifactoryServer());
                resolverContext = new ResolverContext(wrapper.getResolverArtifactoryServer(),
                        wrapper.getResolveDetails(), resolverCredentials);
            }

            ArtifactoryClientConfiguration configuration =
                ExtractorUtils.addBuilderInfoArguments(env, build, buildListener, publisherContext, resolverContext);
            buildListener.getLogger().println("[JFROG] Set the environment for builder #" + buildStepCounter + " "
                    + builder.hashCode() + " wrapper " + ((wrapper == null) ? "none" : wrapper.hashCode())
                    + " is last " + isLastEnabledMavenBuilder);
            propertiesFilePath = configuration.getPropertiesFile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        initialized = true;

        env.put(BuildInfoConfigProperties.PROP_PROPS_FILE, propertiesFilePath);
    }

    private FilePath copyFile(String sourceFile, String targetFilename, String targetExt) {
        URL resource = getClass().getClassLoader().getResource(
                "org/jfrog/hudson/plugins/artifactory/maven3extractor/" + sourceFile);
        if (resource == null) {
            throw new IllegalStateException(sourceFile + " file not found");
        }
        try {
            FilePath remoteClassworlds = build.getWorkspace().createTextTempFile(targetFilename, targetExt, "", false);
            remoteClassworlds.copyFrom(resource);
            return remoteClassworlds;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isMavenVersionValid() {
        return true; //TODO should actually check for Maven 3.0.2 at least
    }

    @Override
    public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {

        if ( aggregateArtifactsPath != null ){
            new FilePath( new File( aggregateArtifactsPath )).deleteRecursive();
        }

        if (!Boolean.toString(false).equals(System.getenv("ART_APPEND_MAVEN_OPTS"))) {
            for ( int buildStepNumber = 0; buildStepNumber < builders.size(); buildStepNumber++ ) {
                final Builder builder = builders.get( buildStepNumber );
                if ( isEnabledMavenBuilder( builder )) {
                    (( MavenBuilder ) builder ).getConfig().setMavenOpts( originalMavenOpts[ buildStepNumber ] );
                }
            }
        }

        /*
        if (classworldsConf != null) {
            classworldsConf.delete();
        }
        */

        Result result = build.getResult();
        if (wrapper.isDeployBuildInfo() && result != null && result.isBetterOrEqualTo(Result.SUCCESS)) {
            build.getActions().add(new BuildInfoResultAction(wrapper.getDetails().getArtifactoryUrl(), build));
        }
        return true;
    }


    /**
     * Append custom Maven opts to the existing to the already existing ones. The opt that will be appended is the
     * location Of the plugin for the Maven process to use.
     */
    @SuppressWarnings({ "AssignmentToMethodParameter", "SuppressionAnnotation" })
    public String appendNewMavenOpts( String originalOpts )
        throws IOException {

        if (StringUtils.contains(originalOpts, MAVEN_PLUGIN_OPTS)) {
            buildListener.getLogger().println(
                    "[JFROG] Property '" + MAVEN_PLUGIN_OPTS +
                            "' is already part of MAVEN_OPTS. This is usually a leftover of " +
                            "previous build which was forcibly stopped. Replacing the value with an updated one. " +
                            "Please remove it from the job configuration.");
            // this regex will remove the property and the value (the value either ends with a space or surrounded by quotes
            originalOpts = originalOpts.replaceAll(MAVEN_PLUGIN_OPTS + "=([^\\s\"]+)|" + MAVEN_PLUGIN_OPTS + "=\"([^\"]*)\"", "").
                           trim();
        }

        StringBuilder mavenOpts = new StringBuilder();
        if (StringUtils.isNotBlank(originalOpts)) {
            mavenOpts.append(originalOpts);
        }

        File maven3ExtractorJar = Which.jarFile(BuildInfoRecorder.class);
        try {
            FilePath actualDependencyDirectory =
                    PluginDependencyHelper.getActualDependencyDirectory(build, maven3ExtractorJar);
            mavenOpts.append(" ").append(MAVEN_PLUGIN_OPTS).append("=")
                    .append(quote(actualDependencyDirectory.getRemote())).append(" -D").append(
                    CLASSWORLDS_CONF_KEY).append("=").append(classworldsConf.getRemote());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        buildListener.getLogger().println("*** Using MAVEN_OPTS=" + mavenOpts.toString());

        return mavenOpts.toString();
    }

    /**
     * Adds quotes around strings containing spaces.
     */
    private static String quote(String arg) {

        if (StringUtils.isNotBlank(arg) && arg.indexOf(' ') >= 0) {
            return "\"" + arg + "\"";
        } else {
            return arg;
        }
    }

    @SuppressWarnings({ "AssignmentToNull" , "SuppressionAnnotation" , "FeatureEnvy" })
    private PublisherContext createPublisherContext(Maven3ExtractorWrapper publisher, boolean publishAggregatedArtifacts)
    {
        ServerDetails server = publisher.getDetails();
        return new PublisherContext.Builder().artifactoryServer(publisher.getArtifactoryServer())
                .serverDetails(server).deployerOverrider(publisher).resolverOverrider(publisher)
                .runChecks(publisher.isRunChecks())
                .includePublishArtifacts(publisher.isIncludePublishArtifacts())
                .aggregateArtifactsPath( aggregateArtifactsPath )
                .copyAggregatedArtifacts( publisher.isCopyAggregatedArtifacts())
                .publishAggregatedArtifacts( publishAggregatedArtifacts )
                .violationRecipients(publisher.getViolationRecipients()).scopes(publisher.getScopes())
                .licenseAutoDiscovery(publisher.isLicenseAutoDiscovery())
                .discardOldBuilds(publisher.isDiscardOldBuilds()).deployArtifacts(publisher.isDeployArtifacts())
                .resolveArtifacts(publisher.isResolveArtifacts())
                .includesExcludes(publisher.getArtifactDeploymentPatterns())
                .filterExcludedArtifactsFromBuild(publisher.isFilterExcludedArtifactsFromBuild())
                .skipBuildInfoDeploy(!publisher.isDeployBuildInfo() )
                .includeEnvVars(publisher.isIncludeEnvVars()).envVarsPatterns(publisher.getEnvVarsPatterns())
                .discardBuildArtifacts(publisher.isDiscardBuildArtifacts())
                .matrixParams(publisher.getMatrixParams())
                .enableIssueTrackerIntegration(publisher.isEnableIssueTrackerIntegration())
                .aggregateBuildIssues(publisher.isAggregateBuildIssues())
                .aggregationBuildStatus(publisher.getAggregationBuildStatus()).build();
    }


    private FilePath aggregateDirectory ()
    {
        try { return build.getWorkspace().createTempDir( "aggregate-", "" );}
        catch ( Exception e ){ throw new RuntimeException( "Failed to retrieve aggregate directory", e);}
    }
}
