package com.xpandit.plugins.xrayjenkins.task;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.xpandit.plugins.xrayjenkins.Utils.BuilderUtils;
import com.xpandit.plugins.xrayjenkins.Utils.ConfigurationUtils;
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
import com.xpandit.plugins.xrayjenkins.task.filefilters.OnlyFeatureFilesInPathFilter;
import com.xpandit.xray.exception.XrayClientCoreGenericException;
import com.xpandit.xray.model.FileStream;
import com.xpandit.xray.model.UploadResult;
import com.xpandit.xray.service.XrayTestImporter;
import com.xpandit.xray.service.impl.XrayTestImporterCloudImpl;
import com.xpandit.xray.service.impl.XrayTestImporterImpl;
import com.xpandit.xray.service.impl.delegates.HttpRequestProvider;
import hudson.AbortException;
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
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.xpandit.plugins.xrayjenkins.Utils.ConfigurationUtils.getConfigurationOrFirstAvailable;
import static com.xpandit.plugins.xrayjenkins.Utils.CredentialUtil.getUserScopedCredentialsListBoxModel;

/**
 * This class is responsible for performing the Xray: Cucumber Features Import Task
 */
public class XrayImportFeatureBuilder extends Builder implements SimpleBuildStep {

    private static final String TMP_ZIP_FILENAME = "xray_cucumber_features.zip";
    public static final long ZIP_WAIT_TIME_MILLISECONDS = 10_000L;

    private String serverInstance;
    private String folderPath;
    private String projectKey;
    private String lastModified;//this must be a String because of pipeline projects
    private String testInfo;
    private String preconditions;
    private String credentialId;

    @DataBoundConstructor
    public XrayImportFeatureBuilder(
            String serverInstance,
            String folderPath,
            String projectKey,
            String lastModified,
            String testInfo,
            String preconditions,
            String credentialId
    ) {
        this.serverInstance = serverInstance;
        this.folderPath = folderPath;
        this.projectKey = projectKey;
        this.lastModified = lastModified;
        this.testInfo = testInfo;
        this.preconditions = preconditions;
        this.credentialId = credentialId;
    }

    public String getServerInstance() {
        return this.serverInstance;
    }

    public void setServerInstance(String serverInstance) {
        this.serverInstance = serverInstance;
    }

    public String getFolderPath() {
        return this.folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public String getTestInfo() {
        return this.testInfo;
    }

    public void setTestInfo(String testInfo) {
        this.testInfo = testInfo;
    }

    public String getPreconditions() {
        return this.preconditions;
    }

    public void setPreconditions(String preconditions) {
        this.preconditions = preconditions;
    }

    public String getLastModified() {
        return this.lastModified;
    }

    @DataBoundSetter
    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public String getCredentialId() {
        return this.credentialId;
    }

    public void setCredentialId(String credentialId) {
        this.credentialId = credentialId;
    }

    public String getProjectKey() {
        return this.projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    @Override
    public void perform(
            @Nonnull Run<?, ?> run,
            @Nonnull FilePath workspace,
            @Nonnull Launcher launcher,
            @Nonnull TaskListener listener
    ) throws IOException, InterruptedException {
        XrayInstance xrayInstance = ConfigurationUtils.getConfiguration(this.serverInstance);

        listener.getLogger().println("Starting XRAY: Cucumber Features Import Task...");

        listener.getLogger().println("##########################################################");
        listener.getLogger().println("####   Xray is importing the feature files  ####");
        listener.getLogger().println("##########################################################");

        if (xrayInstance == null) {
            listener.getLogger().println("The server instance is null");
            addFailedOpEnvironmentVariables(run, "The server instance is null", listener);
            throw new AbortException();
        }
        if (StringUtils.isBlank(this.projectKey)) {
            listener.getLogger().println("You must provide the project key");
            addFailedOpEnvironmentVariables(run, "You must provide the project key", listener);
            throw new AbortException();
        }
        if (StringUtils.isBlank(this.folderPath)) {
            listener.getLogger().println("You must provide the directory path");
            addFailedOpEnvironmentVariables(run, "You must provide the directory path", listener);
            throw new AbortException();
        }

        if (StringUtils.isBlank(xrayInstance.getCredentialId()) && StringUtils.isBlank(credentialId)) {
            listener.getLogger().println("This XrayInstance requires an User scoped credential.");

            XrayEnvironmentVariableSetter
                    .failed("This XrayInstance requires an User scoped credential.")
                    .setAction(run, listener);
            throw new AbortException("This XrayInstance requires an User scoped credential.");
        }

        final CredentialResolver credentialResolver = xrayInstance
                .getCredential(run)
                .orElseGet(() -> new CredentialResolver(this.credentialId, run, xrayInstance.getHosting()));
        final HttpRequestProvider.ProxyBean proxyBean = ProxyUtil.createProxyBean();

        final StandardCredentials credentials = credentialResolver.getCredentials();
        if (credentials == null) {
            String credentialIdNotFound = Optional.ofNullable(xrayInstance.getCredentialId())
                    .filter(StringUtils::isNotBlank)
                    .orElse(this.credentialId);
            String errorTxt = String.format(
                    "Unable to create Xray %s feature import client! Credential '%s' not found. For Cloud instances: Secret Text credentials are not allowed",
                    xrayInstance.getHosting().name(),
                    credentialIdNotFound);
            throw new AbortException(errorTxt);
        }

        XrayTestImporter client;
        if (xrayInstance.getHosting() == HostingType.CLOUD) {
            client = ClientFactory.getCloudFeatureImportClient(credentials, proxyBean)
                    .orElseThrow(() -> new XrayJenkinsGenericException("Unable to create Xray Cloud feature import client! (check credential type selected)."));
        } else if (xrayInstance.getHosting() == null || xrayInstance.getHosting() == HostingType.SERVER) {
            client = ClientFactory.getServerFeatureImportClient(xrayInstance.getServerAddress(), credentials, proxyBean)
                    .orElseThrow(() -> new XrayJenkinsGenericException("Unable to create Xray Server feature import client! (check credential type selected)."));
        } else {
            addFailedOpEnvironmentVariables(run, "Hosting type not recognized.", listener);
            throw new XrayJenkinsGenericException("Hosting type not recognized.");
        }

        final UploadResult uploadResult = processImport(run, workspace, client, listener, xrayInstance);

        listener.getLogger().println("Response: (" + uploadResult.getStatusCode() + ") " + uploadResult.getMessage());

        if (uploadResult.isOkStatusCode()) {
            listener.getLogger().println("Successfully imported Feature files");
        }
    }

    private UploadResult processImport(
            final Run<?, ?> run,
            final FilePath workspace,
            final XrayTestImporter client,
            final TaskListener listener,
            final XrayInstance instance
    ) throws IOException, InterruptedException {

        try {
            final Set<String> validFilePaths = FileUtils.getFeatureFileNamesFromWorkspace(workspace,
                                                                                          this.folderPath,
                                                                                          listener);
            final FilePath zipFile = createZipFile(workspace);
            FileStream testInfoFile = null;
            FileStream preconditionsFile = null;

            Path path = Paths.get(this.folderPath);
            FilePath base = workspace;
            if (path.isAbsolute()) {
                base = new FilePath(path.toFile());
            }

            validFilePaths.forEach(filePath -> listener.getLogger().println("File found: " + filePath));
            listener.getLogger()
                    .println(
                            "Creating zip to import feature files. This may take a while if you have a big number of files.");

            base.zip(zipFile.write(), new OnlyFeatureFilesInPathFilter(validFilePaths, lastModified));

            if (StringUtils.isNotBlank(this.testInfo)) {

                listener.getLogger().println("Getting Test Info file...");

                FilePath filePath = getFile(workspace, this.testInfo, listener);
                testInfoFile = new FileStream(filePath.getName(),
                                              filePath.read(),
                                              ContentType.APPLICATION_JSON);
            }

            if (StringUtils.isNotBlank(this.preconditions)) {

                listener.getLogger().println("Getting preconditions file...");

                FilePath filePath = getFile(workspace, this.preconditions, listener);
                preconditionsFile = new FileStream(filePath.getName(),
                                                   filePath.read(),
                                                   ContentType.APPLICATION_JSON);
            }

            UploadResult uploadResult = uploadZipFile(client, listener, zipFile, testInfoFile, preconditionsFile);

            final HostingType hostingType = instance.getHosting() == null ? HostingType.SERVER : instance.getHosting();
            XrayEnvironmentVariableSetter
                    .parseCucumberFeatureImportResponse(Collections.singleton(uploadResult),
                                                        hostingType,
                                                        listener.getLogger())
                    .setAction(run, listener);

            // Deletes the Zip File
            deleteFile(zipFile, listener);

            return uploadResult;
        } catch (XrayClientCoreGenericException | IOException e) {
            addFailedOpEnvironmentVariables(run, listener);
            listener.error(e.getMessage());
            throw new AbortException(e.getMessage());
        }
    }

    private FilePath getFile(
            FilePath workspace,
            String filePath,
            TaskListener listener
    ) throws IOException, InterruptedException {
        if (workspace == null) {
            throw new XrayJenkinsGenericException("No workspace in this current node");
        }

        if (StringUtils.isBlank(filePath)) {
            throw new XrayJenkinsGenericException("No file path was specified");
        }

        FilePath file = FileUtils.readFile(workspace, filePath.trim(), listener);
        if (file.isDirectory() || !file.exists()) {
            throw new XrayJenkinsGenericException("File path is a directory or the file doesn't exist");
        }
        return file;
    }

    private void deleteFile(FilePath file, TaskListener listener) throws IOException, InterruptedException {
        try {
            file.delete();
            listener.getLogger().println("Temporary file: " + file.getRemote() + " deleted");
        } catch (IOException | InterruptedException e) {
            listener.getLogger().println("Unable to delete temporary file: " + file.getRemote());
            throw e;
        }
    }

    private UploadResult uploadZipFile(
            XrayTestImporter client,
            TaskListener listener,
            FilePath zipFile,
            FileStream testInfo,
            FileStream preconditions
    ) throws IOException, InterruptedException {
        if (zipFile.isRemote()) {
            listener.getLogger().println("Waiting 10 seconds to finish the zip before making the request");
            Thread.sleep(ZIP_WAIT_TIME_MILLISECONDS);
        }
        
        FileStream zipFileStream = new FileStream(
                zipFile.getName(),
                zipFile.read(),
                ContentType.APPLICATION_JSON);
        UploadResult uploadResult = client.importFeatures(this.projectKey, zipFileStream, testInfo, preconditions);
        listener.getLogger().println(uploadResult.getMessage());

        return uploadResult;
    }

    private FilePath createZipFile(final FilePath workspace) {
        return new FilePath(workspace, TMP_ZIP_FILENAME);
    }

    private void addFailedOpEnvironmentVariables(Run<?, ?> run, TaskListener taskListener) {
        addFailedOpEnvironmentVariables(run, null, taskListener);
    }

    private void addFailedOpEnvironmentVariables(Run<?, ?> run, String message, TaskListener taskListener) {
        XrayEnvironmentVariableSetter
                .failed(message)
                .setAction(run, taskListener);
    }

    @Extension
    public static class Descriptor extends BuildStepDescriptor<Builder> {

        @Override
        public synchronized void load() {
            super.load();
        }

        @Override
        @Nonnull
        public String getDisplayName() {
            return "Xray: Cucumber Features Import Task";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return BuilderUtils.isSupportedJobType(jobType);
        }

        public List<XrayInstance> getServerInstances() {
            return ServerConfiguration.get().getServerInstances();
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

        public FormValidation doCheckFolderPath(@QueryParameter String folderPath) {
            return StringUtils.isNotBlank(folderPath)
                    ? FormValidation.ok()
                    : FormValidation.error("You must specify the base directory.");
        }

        public FormValidation doCheckServerInstance(@AncestorInPath Item item) {
            if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                return FormValidation.ok();
            }
            return ConfigurationUtils.anyAvailableConfiguration() ?
                    FormValidation.ok() :
                    FormValidation.error("No configured Server Instances.");
        }

        public FormValidation doCheckProjectKey(@QueryParameter String projectKey) {
            return StringUtils.isNotBlank(projectKey)
                    ? FormValidation.ok()
                    : FormValidation.error("You must specify the Project key");
        }

        public FormValidation doCheckLastModified(@QueryParameter String lastModified) {
            if (StringUtils.isBlank(lastModified)) {
                return FormValidation.ok();
            }
            try {
                return Integer.parseInt(lastModified) > 0
                        ? FormValidation.ok()
                        : FormValidation.error("The value cannot be negative nor 0");
            } catch (NumberFormatException e) {
                return FormValidation.error("The value must be a positive integer");
            }
        }

        public FormValidation doCheckCredentialId(@QueryParameter String value, @QueryParameter String serverInstance) {
            final XrayInstance xrayInstance = getConfigurationOrFirstAvailable(serverInstance);
            if (xrayInstance != null && StringUtils.isBlank(xrayInstance.getCredentialId()) && StringUtils.isBlank(value)) {
                return FormValidation.error("This XrayInstance requires an User scoped credential.");
            }
            return FormValidation.ok();
        }

        public String getUuid() {
            return UUID.randomUUID().toString();
        }
    }
}
