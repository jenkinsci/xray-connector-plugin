package com.xpandit.plugins.xrayjenkins.model;

import hudson.model.Run;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a Jira/Xray instance.
 */
public class XrayInstance {
	
	private String configID;
	private String alias;
	private String serverAddress;
	private HostingType hosting;
    private String credentialId;
    private CredentialResolver credentialResolver;

	@DataBoundConstructor
 	public XrayInstance(String configID, String alias, HostingType hosting, String serverAddress, String credentialId) {
		this.configID = StringUtils.isBlank(configID) ? UUID.randomUUID().toString() : configID;
		this.alias = alias;
		this.hosting = hosting;
		this.serverAddress = serverAddress;
		this.credentialId = credentialId;
 	}

    public String getConfigID(){
			return this.configID;
		}
		
	public void setConfigID(String configID){
		this.configID = configID;
	}
		
	public String getAlias() {
		return alias;
	}

	public void setAlias(String alias) {
		this.alias = alias;
	}

	public String getServerAddress() {
		return serverAddress;
	}

	public void setServerAddress(String serverAddress) {
		this.serverAddress = serverAddress;
	}

	public Optional<CredentialResolver> getCredential(final Run<?, ?> runContext) {
		if (this.credentialResolver == null && StringUtils.isNotBlank(this.credentialId)) {
			this.credentialResolver = new CredentialResolver(this.credentialId, runContext, this.hosting);
		}

		return Optional.ofNullable(this.credentialResolver);
	}
	
	public HostingType getHosting() { return hosting; }

	public void setHosting(HostingType hosting) { this.hosting = hosting; }

	@Nullable
	public String getCredentialId() {
		return credentialId;
	}

	public void setCredentialId(@Nullable String credentialId) {
		this.credentialId = credentialId;
	}
}

