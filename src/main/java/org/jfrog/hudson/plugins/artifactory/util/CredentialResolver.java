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

package org.jfrog.hudson.plugins.artifactory.util;

import org.jfrog.hudson.plugins.artifactory.DeployerOverrider;
import org.jfrog.hudson.plugins.artifactory.ResolverOverrider;
import org.jfrog.hudson.plugins.artifactory.config.ArtifactoryServer;
import org.jfrog.hudson.plugins.artifactory.config.Credentials;

/**
 * A utility class the helps find the preferred credentials to use out of each setting and server
 *
 * @author Noam Y. Tenne
 */
public abstract class CredentialResolver {

    private CredentialResolver() {
    }

    /**
     * Decides and returns the preferred deployment credentials to use from this builder settings and selected server
     *
     * @param overrider Deploy-overriding capable builder
     * @param server    Selected Artifactory server
     * @return Preferred deployment credentials
     */
    public static Credentials getPreferredDeployer(DeployerOverrider overrider, ArtifactoryServer server) {
        if (overrider != null && overrider.isOverridingDefaultDeployer()) {
            return overrider.getOverridingDeployerCredentials();
        }
        return getPreferredDeployer(server);
    }

    /**
     * Decides and returns the preferred deployment credentials to use from this builder settings and selected server
     *
     * @param server    Selected Artifactory server
     * @return Preferred deployment credentials
     */
    public static Credentials getPreferredDeployer(ArtifactoryServer server) {

        if (server != null) {
            Credentials deployerCredentials = server.getDeployerCredentials();
            if (deployerCredentials != null) {
                return deployerCredentials;
            }
        }

        return new Credentials(null, null);
    }


    /**
     * Decides and returns the preferred deployment credentials to use from this builder settings and selected server
     *
     * @param overrider Deploy-overriding capable builder
     * @param server    Selected Artifactory server
     * @return Preferred deployment credentials
     */
    public static Credentials getPreferredResolver(ResolverOverrider overrider, ArtifactoryServer server) {
        if (overrider.isOverridingDefaultResolver()) {
            return overrider.getOverridingResolverCredentials();
        }

        return server.getResolvingCredentials();
    }
}
