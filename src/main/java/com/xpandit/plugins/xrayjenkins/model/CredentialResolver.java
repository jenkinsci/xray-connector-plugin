package com.xpandit.plugins.xrayjenkins.model;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.xpandit.plugins.xrayjenkins.Utils.CredentialUtil;
import hudson.model.Cause;
import hudson.model.Run;
import hudson.model.User;
import java.util.Collections;
import org.acegisecurity.Authentication;
import org.apache.commons.lang.StringUtils;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

public class CredentialResolver {
    private final String credentialId;
    private final Run<?, ?> run;
    private final HostingType hostingType;

    private StandardCredentials credentials;

    public CredentialResolver(final String credentialId, final Run<?, ?> run, final HostingType hostingType) {
        this.credentialId = credentialId;
        this.run = run;
        this.hostingType = hostingType;
    }

    @Nullable
    public StandardCredentials getCredentials() {
        resolveCredential();
        return this.credentials;
    }
    
    private void resolveCredential() {
        if (StringUtils.isNotBlank(this.credentialId)) {
            this.credentials = findCredentialById();
        }
    }

    @Nullable
    private StandardCredentials findCredentialById() {
        // Find the Credential at "System" level.
        final StandardCredentials credential = findSystemCredentialById();
        if (credential != null) {
            return credential;
        }

        // Find the Credential at "User" (who is running the build) level.
        final Authentication buildUserAuth = Optional.ofNullable(run.getCause(Cause.UserIdCause.class))
                                                          .map(Cause.UserIdCause::getUserId)
                                                          .filter(StringUtils::isNotBlank)
                                                          .map(userId -> User.getById(userId, false))
                                                          .map(User::impersonate)
                                                          .orElse(null);

        List<StandardCredentials> userScopedCredentials = CredentialUtil.getAllUserScopedCredentials(run.getParent(), buildUserAuth);
        StandardCredentials credentialsMatched = userScopedCredentials
                .stream()
                .filter(cred -> StringUtils.equals(cred.getId(), this.credentialId))
                .findFirst()
                .orElse(null);

        if (hostingType == HostingType.CLOUD &&
                credentialsMatched != null &&
                CredentialUtil.hasNonUsernamePasswordCredentials(userScopedCredentials, Collections.singleton(credentialsMatched.getId()))) {
            // If the user selected a Cloud instance and a non-username/password credentials, we must not allow the build to go further.
            return null;
        }

        return credentialsMatched;
    }

    @Nullable
    private StandardCredentials findSystemCredentialById() {
        // First search for a password of type username/password.
        StandardUsernamePasswordCredentials usernamePasswordCredentials =
                CredentialsProvider.findCredentialById(this.credentialId,
                        StandardUsernamePasswordCredentials.class,
                        run,
                        (List<DomainRequirement>) null);

        if (usernamePasswordCredentials != null) {
            return usernamePasswordCredentials;
        }

        // If previous password was not found, then search for a password of type secret text (bearer token).
        return CredentialsProvider.findCredentialById(this.credentialId,
                        StringCredentials.class,
                        run,
                        (List<DomainRequirement>) null);
    }
}
