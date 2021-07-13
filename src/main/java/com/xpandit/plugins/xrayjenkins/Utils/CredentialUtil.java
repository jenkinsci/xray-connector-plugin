package com.xpandit.plugins.xrayjenkins.Utils;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.google.common.collect.Lists;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import jenkins.model.Jenkins;
import org.acegisecurity.Authentication;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import org.apache.commons.collections.CollectionUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

public class CredentialUtil {

    private CredentialUtil() {}

    /**
     * Gets all the System credentials from a given Item context.
     *
     * @param item the context.
     * @return All the System credentials from a given Item context.
     */
    public static List<StandardCredentials> getAllSystemCredentials(@Nullable final Item item) {
        if (Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return getStandardCredentials(item, ACL.SYSTEM);
        }

        return Collections.emptyList();
    }

    /**
     * Checks if the input has any credential ID belonging to any credential type other than UsernamePasswordCredentials.
     *
     * @param credentialIds credentials to Check.
     * @return false, if all credential IDs are of type UsernamePasswordCredentials, true otherwise.
     */
    public static boolean hasNonUsernamePasswordCredentials(Collection<StandardCredentials> credentials, Set<String> credentialIds) {
        return !credentials
                .stream()
                .filter(Objects::nonNull)
                .filter(instance -> credentialIds.contains(instance.getId()))
                .allMatch(UsernamePasswordCredentials.class::isInstance);
    }

    /**
     * Gets all the System credentials from a given Item context, as a ListBoxModel.
     *
     * @param item the context.
     * @param credentialId the previously selected Credential ID.
     * @return All the System credentials from a given Item context.
     */
    public static ListBoxModel getAllCredentialsListBoxModel(@Nullable final Item item, final String credentialId) {
        return getCredentialsListBoxModel(credentialId, getAllSystemCredentials(item));
    }

    /**
     * Gets all the User credentials (for the currently logged in user) from a given Item context.
     *
     * @param item the context.
     * @return All the User credentials from a given Item context.
     */
    public static List<StandardCredentials> getAllUserScopedCredentials(@Nullable final Item item) {
        return getAllUserScopedCredentials(item, Jenkins.getAuthentication());
    }

    /**
     * Gets all the User credentials from a given Item context.
     *
     * @param item the context.
     * @param authentication the authentication of the user where the Credentials are located.
     * @return All the User credentials from a given Item context.
     */
    public static List<StandardCredentials> getAllUserScopedCredentials(@Nullable final Item item,
                                                                                        @Nullable final Authentication authentication) {
        return Jenkins.get().hasPermission(CredentialsProvider.USE_OWN) ?
                getStandardCredentials(item, authentication) :
                Collections.emptyList();
    }

    /**
     * Gets all the User credentials from a given Item context, as a ListBoxModel.
     *
     * @param item the context.
     * @param credentialId the previously selected Credential ID.
     * @return All the User credentials from a given Item context.
     */
    public static ListBoxModel getUserScopedCredentialsListBoxModel(@Nullable final Item item, final String credentialId) {
        return getCredentialsListBoxModel(credentialId, getAllUserScopedCredentials(item));
    }

    private static ListBoxModel getCredentialsListBoxModel(final String credentialId,
                                                           final List<StandardCredentials> credentials) {
        final StandardListBoxModel result = new StandardListBoxModel();

        result.includeEmptyValue();
        for (StandardCredentials credential : credentials) {
            result.with(credential);
        }

        return result.includeCurrentValue(credentialId);
    }

    private static List<StandardCredentials> getStandardCredentials(
            @Nullable Item item,
            @Nullable Authentication authentication
    ) {
        List<StandardUsernamePasswordCredentials> usernamePasswordCredentials = CredentialsProvider.lookupCredentials(
                StandardUsernamePasswordCredentials.class,
                item,
                authentication,
                Collections.emptyList());
        List<StringCredentials> secretTextCredentials = CredentialsProvider.lookupCredentials(
                StringCredentials.class,
                item,
                authentication,
                Collections.emptyList());

        List<StandardCredentials> credentials = Lists.newArrayList();
        credentials.addAll(usernamePasswordCredentials);
        credentials.addAll(secretTextCredentials);

        return Collections.unmodifiableList(credentials);
    }
}
