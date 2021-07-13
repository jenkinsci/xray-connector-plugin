package com.xpandit.plugins.xrayjenkins.task;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.xpandit.plugins.xrayjenkins.Utils.BuilderUtils;
import com.xpandit.plugins.xrayjenkins.Utils.ConfigurationUtils;
import com.xpandit.plugins.xrayjenkins.Utils.CredentialUtil;
import com.xpandit.plugins.xrayjenkins.Utils.FileUtils;
import com.xpandit.plugins.xrayjenkins.Utils.FormUtils;
import com.xpandit.plugins.xrayjenkins.Utils.ProxyUtil;
import com.xpandit.plugins.xrayjenkins.exceptions.XrayJenkinsGenericException;
import com.xpandit.plugins.xrayjenkins.factory.ClientFactory;
import com.xpandit.plugins.xrayjenkins.model.CredentialResolver;
import com.xpandit.plugins.xrayjenkins.model.HostingType;
import com.xpandit.plugins.xrayjenkins.model.ServerConfiguration;
import com.xpandit.plugins.xrayjenkins.model.XrayInstance;
import com.xpandit.plugins.xrayjenkins.services.enviromentvariables.XrayEnvironmentVariableSetter;
import com.xpandit.plugins.xrayjenkins.task.compatibility.XrayExportBuilderCompatibilityDelegate;
import com.xpandit.xray.exception.XrayClientCoreGenericException;
import com.xpandit.xray.service.XrayExporter;
import com.xpandit.xray.service.impl.XrayExporterCloudImpl;
import com.xpandit.xray.service.impl.XrayExporterImpl;
import com.xpandit.xray.service.impl.delegates.HttpRequestProvider;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.Optional;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.xpandit.plugins.xrayjenkins.Utils.ConfigurationUtils.getConfiguration;
import static com.xpandit.plugins.xrayjenkins.Utils.ConfigurationUtils.getConfigurationOrFirstAvailable;
import static com.xpandit.plugins.xrayjenkins.Utils.CredentialUtil.getUserScopedCredentialsListBoxModel;
import static com.xpandit.plugins.xrayjenkins.Utils.EnvironmentVariableUtil.expandVariable;

/**
 * This class is responsible for performing the Xray: Cucumber Features Export Task
 * development guidelines for compatibility:
 * The class internal structure was modified in version 1.3.0 so the builder could be compatible with pipeline projects.
 * When developing in this class, compatibility for pré-1.3.0 versions must be ensured.
 * The following cases must always be considered:
 * 1 - 	the job is being created in version 1.3.0 or higher and the deprecated fields must be
 * 		populated for the case the user needs to perform a downgrade.
 *
 * 2 - 	the job was created on a pré-1.3.0 version, but has never been runned in 1.3.0 or higher versions.
 * 		In this case, if the user opens the job configurations, the fields must be populated.
 *
 * 3 - 	the job was created on pré-1.3.0. blueprint String fields need to be populated with values on perform.
 *
 * Any possible scenario must also be considered.
 * @see com.xpandit.plugins.xrayjenkins.task.compatibility.XrayExportBuilderCompatibilityDelegate
 */
public class XrayExportBuilder extends Builder implements SimpleBuildStep {

    private static final Logger LOG = LoggerFactory.getLogger(XrayExportBuilder.class);

    /**
     * this is only kept for backward compatibility (previous from 1.3.0)
     * In the future, when there is no risk that any client is still using legacy versions, we should consider removing it.
     * @deprecated since version 1.3.0, use blue print String fields instead.
     */
    @Deprecated
    private XrayInstance xrayInstance;

    /**
     * this is only kept for backward compatibility (previous from 1.3.0)
     * In the future, when there is no risk that any client is still using legacy versions, we should consider removing it.
     * @deprecated since version 1.3.0, use blue print String fields instead.
     */
    @Deprecated
    private Map<String,String> fields;

    private String serverInstance;//Configuration ID of the Jira instance
    private String issues;
    private String filter;
    private String filePath;
    private String credentialId;

    /**
     * Constructor used in pipelines projects
     *
     * "Anyway code run from Pipeline should take any configuration values as literal strings
     * and make no attempt to perform variable substitution"
     * @see <a href="https://jenkins.io/doc/developer/plugin-development/pipeline-integration/">Writing Pipeline-Compatible Plugins </a>
     * @param serverInstance the server configuration id
     * @param issues the issues to export
     * @param filter the saved filter id
     * @param filePath the file path to export
     */
    @DataBoundConstructor
	public XrayExportBuilder(String serverInstance,
                             String issues,
                             String filter,
                             String filePath,
                             String credentialId){
        this.issues = issues;
        this.filter = filter;
        this.filePath = filePath;
        this.serverInstance = serverInstance;
        this.credentialId = credentialId;

        //compatibility assigns
        this.xrayInstance = ConfigurationUtils.getConfiguration(serverInstance);
        this.fields = getFieldsMap(issues, filter, filePath);
    }

    private Map<String, String> getFieldsMap(String issues,
                                             String filter,
                                             String filePath){
        Map<String, String> fields = new HashMap<>();
        if(StringUtils.isNotBlank(issues)){
            fields.put("issues", issues);
        }
        if(StringUtils.isNotBlank(filter)){
            fields.put("filter", filter);
        }
        if(StringUtils.isNotBlank(filePath)){
            fields.put("filePath", filePath);
        }
        return fields;
    }

    @Override
    public void perform(Run<?,?> build,
                        FilePath workspace,
                        Launcher launcher,
                        TaskListener listener) throws IOException {

        XrayExportBuilderCompatibilityDelegate compatibilityDelegate = new XrayExportBuilderCompatibilityDelegate(this);
        compatibilityDelegate.applyCompatibility();
        
        listener.getLogger().println("Starting XRAY: Cucumber Features Export Task...");
        
        listener.getLogger().println("##########################################################");
        listener.getLogger().println("####   Xray is exporting the feature files  ####");
        listener.getLogger().println("##########################################################");
        XrayInstance xrayInstance = getConfiguration(this.serverInstance);

        if (xrayInstance == null){
            listener.getLogger().println("XrayInstance is null. please check the passed configuration ID");

            XrayEnvironmentVariableSetter
                    .failed("XrayInstance is null. please check the passed configuration ID")
                    .setAction(build, listener);
            throw new AbortException("The Jira server configuration of this task was not found.");
        } else if (StringUtils.isBlank(xrayInstance.getCredentialId()) && StringUtils.isBlank(credentialId)) {
            listener.getLogger().println("This XrayInstance requires an User scoped credential.");

            XrayEnvironmentVariableSetter
                    .failed("This XrayInstance requires an User scoped credential.")
                    .setAction(build, listener);
            throw new AbortException("This XrayInstance requires an User scoped credential.");
        }

        final CredentialResolver credentialResolver = xrayInstance
                .getCredential(build)
                .orElseGet(() -> new CredentialResolver(credentialId, build, xrayInstance.getHosting()));
        final HttpRequestProvider.ProxyBean proxyBean = ProxyUtil.createProxyBean();

        final StandardCredentials credentials = credentialResolver.getCredentials();
        if (credentials == null) {
            String credentialIdNotFound = Optional.ofNullable(xrayInstance.getCredentialId())
                    .filter(StringUtils::isNotBlank)
                    .orElse(this.credentialId);
            String errorTxt = String.format(
                    "Unable to create Xray %s feature export client! Credential '%s' not found. For Cloud instances: Secret Text credentials are not allowed",
                    xrayInstance.getHosting().name(),
                    credentialIdNotFound);
            throw new AbortException(errorTxt);
        }

        XrayExporter client;
        if (xrayInstance.getHosting() == HostingType.CLOUD) {
            client = ClientFactory.getCloudFeatureExportClient(credentials, proxyBean)
                    .orElseThrow(() -> new XrayJenkinsGenericException("Unable to create Xray Cloud feature export client! (check credential type selected)."));
        } else if (xrayInstance.getHosting() == null || xrayInstance.getHosting() == HostingType.SERVER) {
            client = ClientFactory.getServerFeatureExportClient(xrayInstance.getServerAddress(), credentials, proxyBean)
                    .orElseThrow(() -> new XrayJenkinsGenericException("Unable to create Xray Server feature export client! (check credential type selected)."));
        } else {
            XrayEnvironmentVariableSetter
                    .failed("Hosting type not recognized.")
                    .setAction(build, listener);
            throw new XrayJenkinsGenericException("Hosting type not recognized.");
        }
        
        try {
            final EnvVars env = build.getEnvironment(listener);
            final String expandedIssues = expandVariable(env, issues);
            final String expandedFilter = expandVariable(env, filter);
            final String expandedFilePath = expandVariable(env, filePath);

            if (StringUtils.isNotBlank(expandedIssues)) {
                listener.getLogger().println("Issues: " + expandedIssues);
            }
            if (StringUtils.isNotBlank(expandedFilter)) {
                listener.getLogger().println("Filter: " + expandedFilter);
            }
            if (StringUtils.isNotBlank(expandedFilePath)) {
                listener.getLogger().println("Will save the feature files in: " + expandedFilePath);
            }
            
            InputStream file = client.downloadFeatures(expandedIssues, expandedFilter,"true");
            this.unzipFeatures(listener, workspace, expandedFilePath, file);
            FileUtils.closeSilently(file);
            
            listener.getLogger().println("Successfully exported the Cucumber features");

            // Sets the Xray Build Environment Variables
            XrayEnvironmentVariableSetter
                    .success()
                    .setAction(build, listener);
        } catch (XrayClientCoreGenericException | IOException | InterruptedException e) {
            e.printStackTrace();
            listener.error(e.getMessage());

            XrayEnvironmentVariableSetter
                    .failed()
                    .setAction(build, listener);

            throw new AbortException(e.getMessage());
        }
    }
    
    private void unzipFeatures(TaskListener listener, FilePath workspace, String filePath, InputStream zip) throws IOException, InterruptedException {

        if (StringUtils.isBlank(filePath)) {
            filePath = "features/";
        }

        FilePath outputFile = new FilePath(workspace, filePath.trim());
        listener.getLogger().println("###################### Unzipping file ####################");
        outputFile.mkdirs();
        outputFile.unzipFrom(zip);
        listener.getLogger().println("###################### Unzipped file #####################");
    }

    
    public String getServerInstance() {
		return serverInstance;
	}

	public void setServerInstance(String serverInstance) {
		this.serverInstance = serverInstance;
	}


	public String getIssues() {
		return issues;
	}

	public void setIssues(String issues) {
		this.issues = issues;
	}


	public String getFilter() {
		return filter;
	}

	public void setFilter(String filter) {
		this.filter = filter;
	}


	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

    public String getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(String credentialId) {
        this.credentialId = credentialId;
    }

    public XrayInstance getXrayInstance() {
        return xrayInstance;
    }

    @DataBoundSetter
    public void setXrayInstance(XrayInstance xrayInstance) {
        this.xrayInstance = xrayInstance;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    @DataBoundSetter
    public void setFields(Map<String, String> fields) {
        this.fields = fields;
    }

    @Extension
    public static class Descriptor extends BuildStepDescriptor<Builder> {

        public Descriptor() {
        	super(XrayExportBuilder.class);
            load();
        }

        @Override
        public synchronized void load() {
            super.load();
        }
        
        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
           
        	save();
            return true;
            
        }
        
        @Override
		public XrayExportBuilder newInstance(StaplerRequest req, JSONObject formData) throws Descriptor.FormException{
			validateFormData(formData);
        	Map<String,String> fields = getFields(formData.getJSONObject("fields"));
            return new XrayExportBuilder(formData.getString("serverInstance"),
                    fields.get("issues"),
                    fields.get("filter"),
                    fields.get("filePath"),
                    formData.getString("credentialId"));
			
        }

        private void validateFormData(JSONObject formData) throws Descriptor.FormException{
            if(StringUtils.isBlank(formData.getString("serverInstance"))){
                throw new Descriptor.FormException("Xray Cucumber Features Export Task error, you must provide a valid Jira Instance","serverInstance");
            }
        }

        
        public ListBoxModel doFillServerInstanceItems(@AncestorInPath Item item) {
            if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                return new ListBoxModel();
            }
            return FormUtils.getServerInstanceItems();
        }

        public ListBoxModel doFillCredentialIdItems(@AncestorInPath Item item, @QueryParameter String credentialId) {
            return getUserScopedCredentialsListBoxModel(item, credentialId);
        }

        private Map<String, String> getFields(JSONObject configuredFields) {
        	Map<String,String> fields = new HashMap<>();
        	for(String key : (Set<String>) configuredFields.keySet()){
        		if(configuredFields.containsKey(key)){
        			String value = configuredFields.getString(key);
					if(StringUtils.isNotBlank(value))
						fields.put(key, value);
        		}
        	}
        	
        	return Collections.unmodifiableMap(fields);
		}
		
		@Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            LOG.info("applying XrayExportBuilder to following jobType class: {}", jobType.getSimpleName());
            return BuilderUtils.isSupportedJobType(jobType);
        }

        @Override
        public String getDisplayName() {
            return "Xray: Cucumber Features Export Task";
        }

        /*
         * Checking if the file path doesn't contain "../"
         */
        public FormValidation doCheckFilePath(@QueryParameter String value) {

            if(value.contains("../")){
                return FormValidation.error("You can't provide file paths for upper directories.Please don't use \"../\".");
            }
            else{
                return FormValidation.ok();
            }
        }

        /*
         * Checking if either issues or filter is filled
         */
        public FormValidation doCheckIssues(@QueryParameter String value, @QueryParameter String filter) {
            if (StringUtils.isEmpty(value) && StringUtils.isEmpty(filter)) {
                return FormValidation.error("You must provide issue keys and/or a filter ID in order to export cucumber features from Xray.");
            }
            else{
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckFilter(@QueryParameter String value, @QueryParameter String issues) {            
            if (StringUtils.isEmpty(value) && StringUtils.isEmpty(issues)) {
                return FormValidation.error("You must provide issue keys and/or a filter ID in order to export cucumber features from Xray.");
            }
            else{
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckServerInstance(@AncestorInPath Item item) {
            if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                return FormValidation.ok();
            }
            return ConfigurationUtils.anyAvailableConfiguration() ?
                    FormValidation.ok() :
                    FormValidation.error("No configured Server Instances.");
        }

        public FormValidation doCheckCredentialId(@QueryParameter String value, @QueryParameter String serverInstance) {
            final XrayInstance xrayInstance = getConfigurationOrFirstAvailable(serverInstance);
            if (xrayInstance != null && StringUtils.isBlank(xrayInstance.getCredentialId()) && StringUtils.isBlank(value)) {
                return FormValidation.error("This XrayInstance requires an User scoped credential.");
            }
            return FormValidation.ok();
        }
        
        public List<XrayInstance> getServerInstances() {
			return ServerConfiguration.get().getServerInstances();
		}

		public String getUuid() {
            return UUID.randomUUID().toString();
        }
    }

}
