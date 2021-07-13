package com.xpandit.plugins.xrayjenkins.model;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xpandit.plugins.xrayjenkins.Utils.CredentialUtil;
import com.xpandit.plugins.xrayjenkins.Utils.ProxyUtil;
import com.xpandit.plugins.xrayjenkins.factory.ClientFactory;
import com.xpandit.xray.service.XrayClient;
import com.xpandit.xray.service.XrayCloudCredentials;
import com.xpandit.xray.service.XrayServerCredentials;
import com.xpandit.xray.service.impl.XrayClientImpl;
import com.xpandit.xray.service.impl.XrayCloudClientImpl;
import com.xpandit.xray.service.impl.bean.ConnectionResult;
import com.xpandit.xray.service.impl.delegates.HttpRequestProvider;
import hudson.Extension;
import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.cloudbees.plugins.credentials.CredentialsMatchers.withId;
import static com.xpandit.plugins.xrayjenkins.Utils.CredentialUtil.getAllSystemCredentials;
import static com.xpandit.plugins.xrayjenkins.Utils.CredentialUtil.getAllCredentialsListBoxModel;

@Extension
public class ServerConfiguration extends GlobalConfiguration {

    private static final int MAX_ERROR_TEXT_LENGTH = 200; // This is around 2-3 lines in the Server Configuration UI.

    private static final Logger logger = LoggerFactory.getLogger(ServerConfiguration.class);

    private List<XrayInstance> serverInstances = new ArrayList<>();
	
	public ServerConfiguration(){
		load();
        checkForCompatibility();
	}
	
	@Override
    public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
        checkInvalidCloudCredentials(formData);

        req.bindJSON(this, formData.getJSONObject("xrayinstance"));
        save();
        return true;
    }

    private void checkInvalidCloudCredentials(JSONObject formData) throws FormException {
        if (!formData.has("xrayinstance")) {
            return;
        }
        JSONObject xrayInstances = formData.getJSONObject("xrayinstance");
        if (!xrayInstances.has("serverInstances")) {
            return;
        }
        String xrayInstancesJson = xrayInstances.getJSONArray("serverInstances").toString();

        Type listType = new TypeToken<List<XrayInstance>>(){}.getType();
        List<XrayInstance> instances = new Gson().fromJson(xrayInstancesJson, listType);

        Set<String> cloudCredentialIds = instances
                .stream()
                .filter(instance -> instance.getHosting() == HostingType.CLOUD)
                .map(XrayInstance::getCredentialId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

        if (CollectionUtils.isNotEmpty(cloudCredentialIds) &&
                CredentialUtil.hasNonUsernamePasswordCredentials(CredentialUtil.getAllSystemCredentials(null), cloudCredentialIds)) {
            throw new FormException("[Xray connector] Jira cloud instances can either be empty or have credentials of type Username/Password.", "xrayinstance");
        }
    }

    public void setServerInstances(List<XrayInstance> serverInstances){
        this.serverInstances = serverInstances;
    }

    public List<XrayInstance> getServerInstances(){
		return this.serverInstances;
	}

	public String getCloudHostingTypeName(){
	    return HostingType.getCloudHostingName();
    }

    public String getServerHostingTypeName(){
        return HostingType.getServerHostingName();
    }
	
	public static ServerConfiguration get() {
	    return GlobalConfiguration.all().get(ServerConfiguration.class);
	}

    public ListBoxModel doFillCredentialIdItems(@AncestorInPath Item item, @QueryParameter String credentialId) {
        return getAllCredentialsListBoxModel(item, credentialId);
    }

    public FormValidation doCheckCredentialId(
            @AncestorInPath Item item,
            @QueryParameter String value
    ) {
        if (item == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                || item != null && !item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
            return FormValidation.ok();
        }

        if (StringUtils.isBlank(value)) {
            return FormValidation.warning("Leave the credentials field empty if you want to pick user scoped credentials for each Build Task.");
        }
        
        if (!credentialExists(item, value)) {
            return FormValidation.error("Cannot find currently selected credentials");
        }
        return FormValidation.ok();
    }

    public FormValidation doCheckServerAddress(
            @AncestorInPath final Item item,
            @QueryParameter("hosting") final String hosting,
            @QueryParameter("serverAddress") final String serverAddress
    ) {
        if (item == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                || item != null && !item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
            // If the user is not authenticated, we follow Jenkins standards and always return OK.
            // This is to avoid giving up more information than required.
            return FormValidation.ok();
        }

        // We only need to check the URL for Server instances, since Cloud API URL is hardcoded and controlled by us.
        if (Objects.equals(hosting, HostingType.SERVER.toString()) && StringUtils.isNotBlank(serverAddress)) {
            HttpRequestProvider.ProxyBean proxyBean = ProxyUtil.createProxyBean();
            boolean isJiraInstance = ClientFactory.getServerClientWithoutCredentials(serverAddress, proxyBean)
                    .map(XrayClient::isJiraInstance)
                    .orElse(Boolean.FALSE);

            if (!isJiraInstance) {
                logger.error("URL provided is not from a Jira instance -> {}/rest/api/2/serverInfo didn't return a valid result.", serverAddress);
                return FormValidation.error("URL provided is not from a Jira instance (check if your /serverInfo endpoint is not blocked)");
            }
        }

        return FormValidation.ok();
    }

    @RequirePOST
	public FormValidation doTestConnection(@AncestorInPath final Item item,
                                           @QueryParameter("hosting") final String hosting,
	                                       @QueryParameter("serverAddress") final String serverAddress,
                                           @QueryParameter("credentialId") final String credentialId) {

        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (StringUtils.isBlank(credentialId)) {
            return FormValidation.error("Authentication is optional, however it is required in order to test the connection.");
        }

        if (StringUtils.isBlank(hosting)) {
            return FormValidation.error("Hosting type can't be blank.");
        }

        final StandardCredentials credential = CredentialsMatchers.firstOrNull(getAllSystemCredentials(item), withId(credentialId));
        if (credential == null) {
            return FormValidation.error("Cannot find currently selected credentials");
        }

        final HttpRequestProvider.ProxyBean proxyBean = ProxyUtil.createProxyBean();
        final ConnectionResult connectionResult;

        if (hosting.equals(HostingType.CLOUD.toString())) {
            Optional<XrayCloudCredentials> cloudClient = ClientFactory.getCloudClient(credential, proxyBean);
            if (!cloudClient.isPresent()) {
                return FormValidation.error("Unable to create Xray Cloud client! Cloud instances only support credentials of type Username/Password");
            }

            connectionResult = cloudClient.get().testConnection();
        } else if (hosting.equals(HostingType.SERVER.toString())) {
            if(StringUtils.isBlank(serverAddress)) {
                return FormValidation.error("Server address can't be empty");
            }

            Optional<XrayServerCredentials> xrayClient = ClientFactory.getServerClient(serverAddress, credential, proxyBean);
            if (!xrayClient.isPresent()) {
                return FormValidation.error("Unable to create Xray Server/DC client! (Please check the type of the selected credentials)");
            }

            connectionResult = jiraServerTestConnection(serverAddress, xrayClient.get());
        } else {
            return FormValidation.error("Hosting type not recognized.");
        }

        if (connectionResult.isSuccessful()) {
            return FormValidation.ok("Connection: Success!");
        } else {
            final String errorText = "Could not establish connection.\n" +
                    limitStringSize(connectionResult.getErrorText()) +
                    "\n\nFor more information please check the logs.";

            logger.error("Error while connecting to instance:\n{}", connectionResult.getErrorText());
            return FormValidation.error(errorText);
        }
    }

    private ConnectionResult jiraServerTestConnection(String serverAddress, XrayServerCredentials xrayClient) {
        if (!xrayClient.isJiraInstance()) {
            logger.error("URL provided is not from a Jira instance -> {}/rest/api/2/serverInfo didn't return a valid result.", serverAddress);
            return ConnectionResult.connectionFailed("URL provided is not from a Jira instance (check if your /serverInfo endpoint is not blocked)");
        } else {
            return xrayClient.testConnection();
        }
    }

    private String limitStringSize(final String errorText) {
	    return StringUtils.trim(StringUtils.substring(errorText, 0, MAX_ERROR_TEXT_LENGTH));
    }

    private void checkForCompatibility(){
        for(XrayInstance instance : serverInstances){
            if(instance.getHosting() == null){
                instance.setHosting(HostingType.getDefaultType());
            }
        }
    }
    
    @Nullable
    private StandardCredentials findCredential(@Nullable final Item item, @Nullable final String credentialId) {
	    if (StringUtils.isBlank(credentialId)) {
	        return null;
        }
        return CredentialsMatchers.firstOrNull(getAllSystemCredentials(item), withId(credentialId));
    }

    private boolean credentialExists(@Nullable final Item item, @Nullable final String credentialId) {
        return findCredential(item, credentialId) != null;
    }
}
