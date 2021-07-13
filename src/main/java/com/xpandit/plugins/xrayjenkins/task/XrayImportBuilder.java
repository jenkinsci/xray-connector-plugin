package com.xpandit.plugins.xrayjenkins.task;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import com.xpandit.plugins.xrayjenkins.task.compatibility.XrayImportBuilderCompatibilityDelegate;
import com.xpandit.xray.exception.XrayClientCoreGenericException;
import com.xpandit.xray.model.Content;
import com.xpandit.xray.model.Endpoint;
import com.xpandit.xray.model.FormatBean;
import com.xpandit.xray.model.ParameterBean;
import com.xpandit.xray.model.QueryParameter;
import com.xpandit.xray.model.UploadResult;
import com.xpandit.xray.service.XrayImporter;
import com.xpandit.xray.service.impl.XrayImporterCloudImpl;
import com.xpandit.xray.service.impl.XrayImporterImpl;
import com.xpandit.xray.service.impl.delegates.HttpRequestProvider;
import com.xpandit.xray.util.UploadResultUtil;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.xpandit.plugins.xrayjenkins.Utils.ConfigurationUtils.getConfiguration;
import static com.xpandit.plugins.xrayjenkins.Utils.ConfigurationUtils.getConfigurationOrFirstAvailable;
import static com.xpandit.plugins.xrayjenkins.Utils.CredentialUtil.getUserScopedCredentialsListBoxModel;
import static com.xpandit.plugins.xrayjenkins.Utils.EnvironmentVariableUtil.expandVariable;
import static com.xpandit.xray.util.UploadResultUtil.MAX_RETRY_AFTER_TIME_SECONDS;

/**
 * This class is responsible for performing the Xray: Results Import Task
 * development guidelines for compatibility:
 * The class internal structure was modified in version 1.3.0 so the builder could be compatible with pipeline projects.
 * When developing in this class, compatibility for pré-1.3.0 versions must be ensured.
 * The following cases must always be considered:
 * 1 - 	the job is being created in version 1.3.0 or higher and the deprecated fields must be
 * populated for the case the user needs to perform a downgrade.
 * <p>
 * 2 - 	the job was created on a pré-1.3.0 version, but has never been runned in 1.3.0 or higher versions.
 * In this case, if the user opens the job configurations, the fields must be populated.
 * <p>
 * 3 - 	the job was created on pré-1.3.0. blueprint String fields need to be populated with values on perform.
 * <p>
 * Any possible scenario must also be considered.
 *
 * @see com.xpandit.plugins.xrayjenkins.task.compatibility.XrayImportBuilderCompatibilityDelegate
 */
public class XrayImportBuilder extends Notifier implements SimpleBuildStep {

    private static final Logger LOG = LoggerFactory.getLogger(XrayImportBuilder.class);
    private static Gson gson = new GsonBuilder().create();

    private static final String SAME_EXECUTION_CHECKBOX = "importToSameExecution";
    private static final String IMPORT_IN_PARALLEL = "importInParallel";
    private static final String INPUT_INFO_SWITCHER = "inputInfoSwitcher";
    private static final String TEST_INFO_INPUT_SWITCHER = "inputTestInfoSwitcher";
    private static final String SERVER_INSTANCE = "serverInstance";
    private static final String ERROR_LOG = "Error while performing import tasks";
    private static final String TEST_ENVIRONMENTS = "testEnvironments";
    private static final String PROJECT_KEY = "projectKey";
    private static final String TEST_PLAN_KEY = "testPlanKey";
    private static final String FIX_VERSION = "fixVersion";
    private static final String IMPORT_FILE_PATH = "importFilePath";
    private static final String TEST_EXEC_KEY = "testExecKey";
    private static final String REVISION_FIELD = "revision";
    private static final String IMPORT_INFO = "importInfo";
    private static final String TEST_IMPORT_INFO = "testImportInfo";
    private static final String CREDENTIAL_ID = "credentialId";
    private static final String FORMAT_SUFFIX = "formatSuffix";
    private static final String CLOUD_DOC_URL = "https://confluence.xpand-it.com/display/XRAYCLOUD/Import+Execution+Results+-+REST";
    private static final String SERVER_DOC_URL = "https://confluence.xpand-it.com/display/XRAY/Import+Execution+Results+-+REST";
    private static final String MULTIPART = "multipart";
    private static final int MAX_TRIES = 3;


    private String formatSuffix; //value of format select
    private String serverInstance;//Configuration ID of the Jira instance
    private String inputInfoSwitcher;//value of the input type switcher
    private String inputTestInfoSwitcher;
    private String endpointName;
    private String projectKey;
    private String testEnvironments;
    private String testPlanKey;
    private String fixVersion;
    private String importFilePath;
    private String testExecKey;
    private String revision;
    private String importInfo;
    private String testImportInfo;
    private String importToSameExecution;
    private String importInParallel;
    private String credentialId;


    /**
     * this is only kept for backward compatibility (previous from 1.3.0)
     * In the future, when there is no risk that any client is still using legacy versions, we should consider removing it.
     *
     * @deprecated since version 1.3.0, use blue print String fields instead.
     */
    @Deprecated
    private Map<String, String> dynamicFields;

    /**
     * this is only kept for backward compatibility (previous from 1.3.0)
     * In the future, when there is no risk that any client is still using legacy versions, we should consider removing it.
     *
     * @deprecated since version 1.3.0, use blue print String fields instead.
     */
    @Deprecated
    private XrayInstance xrayInstance;

    /**
     * this is only kept for backward compatibility (previous from 1.3.0)
     * In the future, when there is no risk that any client is still using legacy versions, we should consider removing it.
     *
     * @deprecated since1.3.0, use blue print String fields instead.
     */
    @Deprecated
    private Endpoint endpoint;

    /**
     * This constructor is compatible with pipelines projects
     * <p>
     * "Anyway code run from Pipeline should take any configuration values as literal strings
     * and make no attempt to perform variable substitution"
     *
     * @param serverInstance    the server configuration id
     * @param endpointName      the endpoint to be used
     * @param projectKey        the project key
     * @param testEnvironments  the test environments
     * @param testPlanKey       the test plan key
     * @param fixVersion        the fix version
     * @param importFilePath    the path of the result file to be imported
     * @param testExecKey       the test execution key
     * @param revision          the revision
     * @param importInfo        the importation info file or json content
     * @param inputInfoSwitcher filePath or fileContent switcher
     * @param credentialId
     * @see <a href="https://jenkins.io/doc/developer/plugin-development/pipeline-integration/">Writing Pipeline-Compatible Plugins </a>
     */
    @DataBoundConstructor
    public XrayImportBuilder(
            String serverInstance,
            String endpointName,
            String projectKey,
            String testEnvironments,
            String testPlanKey,
            String fixVersion,
            String importFilePath,
            String testExecKey,
            String revision,
            String importInfo,
            String testImportInfo,
            String inputInfoSwitcher,
            String inputTestInfoSwitcher,
            String importToSameExecution,
            @Nullable String credentialId,
            String importInParallel
    ) {
        this.serverInstance = serverInstance;
        this.endpointName = endpointName;
        this.credentialId = credentialId;
        Endpoint e = lookupForEndpoint();
        this.formatSuffix = e != null ? e.getSuffix() : null;
        this.projectKey = projectKey;
        this.testEnvironments = testEnvironments;
        this.testPlanKey = testPlanKey;
        this.fixVersion = fixVersion;
        this.importFilePath = importFilePath;
        this.testExecKey = testExecKey;
        this.revision = revision;
        this.importInfo = importInfo;
        this.testImportInfo = testImportInfo;
        this.inputInfoSwitcher = inputInfoSwitcher;
        this.inputTestInfoSwitcher = inputTestInfoSwitcher;
        this.importToSameExecution = importToSameExecution;
        this.importInParallel = importInParallel;

        /**
         * Compatibility assigns - when creating the job, the config file must be prepared to run on pré-1.3.0 version
         */
        this.dynamicFields = getDynamicFieldsMap();
        this.xrayInstance = ConfigurationUtils.getConfiguration(serverInstance);
        this.endpoint = lookupForEndpoint();
    }

    private Map<String, String> getDynamicFieldsMap() {
        Map<String, String> fields = new HashMap<>();
        putNotBlank(fields, PROJECT_KEY, projectKey);
        putNotBlank(fields, TEST_ENVIRONMENTS, testEnvironments);
        putNotBlank(fields, TEST_PLAN_KEY, testPlanKey);
        putNotBlank(fields, FIX_VERSION, fixVersion);
        putNotBlank(fields, IMPORT_FILE_PATH, importFilePath);
        putNotBlank(fields, TEST_EXEC_KEY, testExecKey);
        putNotBlank(fields, REVISION_FIELD, revision);
        putNotBlank(fields, IMPORT_INFO, importInfo);
        putNotBlank(fields, INPUT_INFO_SWITCHER, inputInfoSwitcher);
        putNotBlank(fields, TEST_IMPORT_INFO, testImportInfo);
        putNotBlank(fields, TEST_INFO_INPUT_SWITCHER, inputTestInfoSwitcher);
        return fields;
    }

    private void putNotBlank(Map<String, String> fields, String key, String val) {
        if (StringUtils.isNotBlank(val)) {
            fields.put(key, val);
        }
    }

    public Map<String, String> getDynamicFields() {
        return this.dynamicFields;
    }

    @DataBoundSetter
    public void setDynamicFields(Map<String, String> dynamicFields) {
        this.dynamicFields = dynamicFields;
    }

    public XrayInstance getXrayInstance() {
        return this.xrayInstance;
    }

    @DataBoundSetter
    public void setXrayInstance(XrayInstance xrayInstance) {
        this.xrayInstance = xrayInstance;
    }

    public Endpoint getEndpoint() {
        return this.endpoint;
    }

    @DataBoundSetter
    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public String getFormatSuffix() {
        return this.formatSuffix;
    }

    public String getServerInstance() {
        return this.serverInstance;
    }

    public void setServerInstance(String serverInstance) {
        this.serverInstance = serverInstance;
    }

    public void setFormatSuffix(String formatSuffix) {
        this.formatSuffix = formatSuffix;
    }

    public String getEndpointName() {
        return this.endpointName;
    }

    public void setEndpointName(String endpointName) {
        this.endpointName = endpointName;
    }

    public String getProjectKey() {
        return this.projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getTestEnvironments() {
        return this.testEnvironments;
    }

    public void setTestEnvironments(String testEnvironments) {
        this.testEnvironments = testEnvironments;
    }

    public String getTestPlanKey() {
        return this.testPlanKey;
    }

    public void setTestPlanKey(String testPlanKey) {
        this.testPlanKey = testPlanKey;
    }

    public String getFixVersion() {
        return this.fixVersion;
    }

    public void setFixVersion(String fixVersion) {
        this.fixVersion = fixVersion;
    }

    public String getImportFilePath() {
        return this.importFilePath;
    }

    public void setImportFilePath(String importFilePath) {
        this.importFilePath = importFilePath;
    }

    public String getTestExecKey() {
        return this.testExecKey;
    }

    public void setTestExecKey(String testExecKey) {
        this.testExecKey = testExecKey;
    }

    public String getRevision() {
        return this.revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public String getImportInfo() {
        return this.importInfo;
    }

    public void setImportInfo(String importInfo) {
        this.importInfo = importInfo;
    }

    public String getImportToSameExecution() {
        return this.importToSameExecution;
    }

    public void setImportToSameExecution(String importToSameExecution) {
        this.importToSameExecution = importToSameExecution;
    }

    public String getImportInParallel() {
        return importInParallel;
    }

    public void setImportInParallel(String importInParallel) {
        this.importInParallel = importInParallel;
    }

    public String getCredentialId() {
        return this.credentialId;
    }

    public void setCredentialId(String credentialId) {
        this.credentialId = credentialId;
    }

    public String getFormatName() {
        return Endpoint.lookupByName(endpointName).getName();
    }

    public String getInputInfoSwitcher() {
        return this.inputInfoSwitcher;
    }

    public String getInputTestInfoSwitcher() {
        return this.inputTestInfoSwitcher;
    }

    public void setInputInfoSwitcher(String inputInfoSwitcher) {
        this.inputInfoSwitcher = inputInfoSwitcher;
    }

    public String defaultFormats() {

        /*
         * Compatibility fix - the job was created on a pré-1.3.0 version, but has never been runned in post-1.3.0 version.
         * In this case, if the user opens the job configurations, the fields must be populated.
         */
        XrayImportBuilderCompatibilityDelegate delegate = new XrayImportBuilderCompatibilityDelegate(this);
        delegate.applyCompatibility();

        Map<String, FormatBean> formats = new HashMap<>();
        for (Endpoint e : Endpoint.values()) {
            FormatBean bean = e.toBean();
            formats.put(e.getSuffix(), bean);
            Endpoint endpointObj = lookupForEndpoint();
            if (e.name().equals(endpointObj != null ? endpointObj.name() : null)) {
                bean.setFieldsConfiguration(getDynamicFieldsMap());
            }
            addImportToSameExecField(e, bean);
            addImportInParallel(bean);
        }
        return gson.toJson(formats);
    }

    /**
     * Using the browser interface, we will receive the endpoint suffix
     * but in pipeline projects the user must be able to also use the endpoint name
     *
     * @return the matching <code>Endpoint</code> or <code>null</code> if not found.
     */
    @Nullable
    private Endpoint lookupForEndpoint() {
        Endpoint targetedEndpoint = Endpoint.lookupByName(endpointName);
        return targetedEndpoint != null ? targetedEndpoint : Endpoint.lookupBySuffix(endpointName);
    }

    private void addImportToSameExecField(Endpoint e, FormatBean bean) {
        if (BuilderUtils.isGlobExpressionsSupported(e)) {
            ParameterBean pb = new ParameterBean(SAME_EXECUTION_CHECKBOX, "same exec text box", false);
            pb.setConfiguration(importToSameExecution);
            bean.getConfigurableFields().add(0, pb);
        }
    }

    private void addImportInParallel(FormatBean bean) {
        ParameterBean importInParallelpb = new ParameterBean(IMPORT_IN_PARALLEL, "import in parallel text box", false);
        importInParallelpb.setConfiguration(importInParallel);
        bean.getConfigurableFields().add(importInParallelpb);
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

    @Override
    public void perform(
            @Nonnull Run<?, ?> build,
            @Nonnull FilePath workspace,
            @Nonnull Launcher launcher,
            @Nonnull TaskListener listener
    )
            throws InterruptedException, IOException {
        /*
         * Compatibility fix:
         * Forward case - the job was created on pré-1.3.0. blueprint fields need to be populated with values
         * Backward case - due to some bugs fixed in 1.3.0, we will reassign values for deprecated fields for each build
         * @see <a href="https://jira.xpand-addons.com/browse/XRAYJENKINS-11">XRAYJENKINS-11</a>
         */
        XrayImportBuilderCompatibilityDelegate compatibilityDelegate = new XrayImportBuilderCompatibilityDelegate(this);
        compatibilityDelegate.applyCompatibility();

        validate(getDynamicFieldsMap());

        listener.getLogger().println("Starting XRAY: Results Import Task...");

        listener.getLogger().println("##########################################################");
        listener.getLogger().println("####     Xray is importing the execution results      ####");
        listener.getLogger().println("##########################################################");
        XrayInstance importInstance = ConfigurationUtils.getConfiguration(serverInstance);

        if (importInstance == null) {
            XrayEnvironmentVariableSetter
                    .failed("The Jira server configuration of this task was not found.")
                    .setAction(build, listener);
            throw new AbortException("The Jira server configuration of this task was not found.");
        } else if (StringUtils.isBlank(importInstance.getCredentialId()) && StringUtils.isBlank(credentialId)) {
            listener.getLogger().println("This XrayInstance requires an User scoped credential.");

            XrayEnvironmentVariableSetter
                    .failed("This XrayInstance requires an User scoped credential.")
                    .setAction(build, listener);
            throw new AbortException("This XrayInstance requires an User scoped credential.");
        }

        final CredentialResolver credentialResolver = importInstance
                .getCredential(build)
                .orElseGet(() -> new CredentialResolver(this.credentialId, build, importInstance.getHosting()));
        final HttpRequestProvider.ProxyBean proxyBean = ProxyUtil.createProxyBean();
        final HostingType hostingType = importInstance.getHosting() == null
                ? HostingType.SERVER
                : importInstance.getHosting();

        final StandardCredentials credentials = credentialResolver.getCredentials();
        if (credentials == null) {
            String credentialIdNotFound = Optional.ofNullable(importInstance.getCredentialId())
                    .filter(StringUtils::isNotBlank)
                    .orElse(this.credentialId);
            String errorTxt = String.format(
                    "Unable to create Xray %s results import client! Credential '%s' not found. For Cloud instances: Secret Text credentials are not allowed",
                    hostingType.name(),
                    credentialIdNotFound);
            throw new AbortException(errorTxt);
        }

        XrayImporter client;
        if (hostingType == HostingType.CLOUD) {
            client = ClientFactory.getCloudResultsImportClient(credentials, proxyBean)
                    .orElseThrow(() -> new XrayJenkinsGenericException("Unable to create Xray Cloud results import client! (check credential type selected)."));
        } else if (hostingType == HostingType.SERVER) {
            client = ClientFactory.getServerResultsImportClient(importInstance.getServerAddress(), credentials, proxyBean)
                    .orElseThrow(() -> new XrayJenkinsGenericException("Unable to create Xray Server/DC results import client! (check credential type selected)."));
        } else {
            XrayEnvironmentVariableSetter
                    .failed("Hosting type not recognized.")
                    .setAction(build, listener);
            throw new XrayJenkinsGenericException("Hosting type not recognized.");
        }

        EnvVars env = build.getEnvironment(listener);
        String resolved = expandVariable(env, this.importFilePath);

        Endpoint endpointValue = Endpoint.lookupBySuffix(this.endpointName);

        final List<UploadResult> uploadResults = new ArrayList<>();
        
        if (BuilderUtils.isGlobExpressionsSupported(endpointValue)) {
            List<FilePath> files = FileUtils.getFiles(workspace, resolved, listener, launcher.getChannel());
            if ("true".equals(importInParallel) && CollectionUtils.isNotEmpty(files) && files.size() > 1) {
                importResultsInParallel(build, workspace, listener, importInstance, client, env, uploadResults, files);
            } else {
                importResultsSequential(build, workspace, listener, importInstance, client, env, uploadResults, files);
            }
            
        } else {
            FilePath file = getFile(workspace, resolved, listener);
            uploadResults.add(tryUploadResults(workspace, listener, client, file, env, null));
        }

        // Sets the Xray Build Environment Variables
        XrayEnvironmentVariableSetter
                .parseResultImportResponse(uploadResults, hostingType, listener.getLogger())
                .setAction(build, listener);
    }

    private void importResultsSequential(
            Run<?, ?> build,
            FilePath workspace,
            TaskListener listener,
            XrayInstance importInstance,
            XrayImporter client,
            EnvVars env,
            List<UploadResult> uploadResults,
            List<FilePath> files
    ) throws IOException, InterruptedException {
        String key = null;
        if (CollectionUtils.isEmpty(files)) {
            return;
        }
        for (FilePath fp : files) {
            UploadResult result = uploadResults(workspace, listener, client, env, key, fp);
            uploadResults.add(result);
            if (key == null && "true".equals(importToSameExecution)) {
                key = getTestExecutionKeyFromResponse(build, listener, importInstance, result);
            }
        }
    }

    private void importResultsInParallel(
            Run<?, ?> build,
            FilePath workspace,
            TaskListener listener,
            XrayInstance importInstance,
            XrayImporter client,
            EnvVars env,
            List<UploadResult> uploadResults,
            List<FilePath> files
    ) throws InterruptedException, IOException {

        String key = null;

        if ("true".equals(importToSameExecution) && CollectionUtils.isNotEmpty(files)) {
            // When import to same test execution we need to do a first import to get the Test Execution Key
            FilePath file1 = files.get(0);
            UploadResult result = uploadResults(workspace, listener, client, env, null, file1);
            uploadResults.add(result);
            // We remove the file to not import it again below.
            files.remove(0);
            key = getTestExecutionKeyFromResponse(build, listener, importInstance, result);
        }

        final String finalKey = key;
        List<UploadResult> results = files
                .parallelStream()
                .map(file -> getUploadResultImportInParallel(workspace, listener, client, env, finalKey, file))
                .collect(Collectors.toList());
        
        uploadResults.addAll(results);
    }
    
    private UploadResult getUploadResultImportInParallel(
            FilePath workspace,
            TaskListener listener,
            XrayImporter client,
            EnvVars env,
            String finalKey,
            FilePath filePath
    ) {
        try {
            return uploadResults(workspace, listener, client, env, finalKey, filePath);
        } catch (InterruptedException | IOException e) {
            throw new XrayJenkinsGenericException(e);
        }
    }

    private String getTestExecutionKeyFromResponse(
            Run<?, ?> build,
            TaskListener listener,
            XrayInstance importInstance,
            UploadResult result
    ) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String key = null;
        HostingType instanceType = importInstance.getHosting();

        if (instanceType == HostingType.SERVER) {

            Map<String, Object> resultMap = mapper.readValue(result.getMessage(), Map.class);
            if (MapUtils.isNotEmpty(resultMap)) {
                Map<String, String> testExecIssue = (Map<String, String>) resultMap.get("testExecIssue");
                if (MapUtils.isNotEmpty(testExecIssue)) {
                    key = testExecIssue.get("key");
                }
            }
        } else if (instanceType == HostingType.CLOUD) {

            Map<String, String> map = mapper.readValue(result.getMessage(), Map.class);
            key = map.get("key");
        } else {
            throw new XrayJenkinsGenericException("Instance type not found.");
        }

        if (key == null && result.getStatusCode() != HttpStatus.SC_ACCEPTED) {
            XrayEnvironmentVariableSetter
                    .failed("No Test Execution Key returned")
                    .setAction(build, listener);
            throw new XrayJenkinsGenericException("No Test Execution Key returned");
        }
        return key;
    }

    private UploadResult uploadResults(
            @Nonnull FilePath workspace,
            @Nonnull TaskListener listener,
            XrayImporter client,
            EnvVars environmentVariables,
            String key,
            FilePath filePath
    ) throws InterruptedException, IOException {
        UploadResult result = tryUploadResults(workspace, listener, client, filePath, environmentVariables, key);

        int tries = 1; // Tries start with 1 because we count the original request as a try.

        // Xray Cloud may return a 429 (Too Many Requests) response, in this case, we want to retry up to 3 times (after the waiting period).
        while (result.isTooManyRequests() && tries < MAX_TRIES) {
            final long sleepTimeSeconds = UploadResultUtil.getRetryTime(result)
                                                          .orElse(MAX_RETRY_AFTER_TIME_SECONDS);

            listener.getLogger().println("Too Many Requests: Waiting " + sleepTimeSeconds + " seconds - try #" + tries);

            if (sleepTimeSeconds > MAX_RETRY_AFTER_TIME_SECONDS) { // If the server asks us to wait to much time, we abort the whole import.
                final String logText = String.format(
                        "Too Many Requests: Wait time (%s seconds) exceeds the maximum allowed (%s seconds)",
                        sleepTimeSeconds,
                        MAX_RETRY_AFTER_TIME_SECONDS);

                listener.getLogger().println(logText);
                throw new XrayJenkinsGenericException(result.getMessage());
            } else if (sleepTimeSeconds > 0) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(sleepTimeSeconds));
            }

            result = tryUploadResults(workspace, listener, client, filePath, environmentVariables, key);
            tries++;
        }

        if (result.isTooManyRequests() && tries >= MAX_TRIES) {
            throw new XrayJenkinsGenericException(result.getMessage());
        }

        return result;
    }

    /**
     * Upload the results to the xray instance
     *
     * @param workspace            the Workspace
     * @param listener             the TaskListener
     * @param client               the xray client
     * @param resultsFile          the FilePath of the results file
     * @param env                  the environment variables
     * @param sameTestExecutionKey The key used when multiple results are imported to the same Test Execution
     * @return the upload results
     */
    private UploadResult tryUploadResults(
            FilePath workspace,
            TaskListener listener,
            XrayImporter client,
            FilePath resultsFile,
            EnvVars env,
            @Nullable String sameTestExecutionKey
    ) throws InterruptedException, IOException {
        try {
            Endpoint targetEndpoint = lookupForEndpoint();
            Map<com.xpandit.xray.model.QueryParameter, String> queryParams = prepareQueryParam(env);

            if (BuilderUtils.isEnvVariableUndefined(this.testExecKey)
                    && StringUtils.isNotBlank(sameTestExecutionKey)
                    && "true".equals(importToSameExecution)) {
                if (isMultipartEndpoint(targetEndpoint)) {
                    targetEndpoint = BuilderUtils.getGenericEndpointFromMultipartSuffix(targetEndpoint.getSuffix());
                }

                queryParams.put(com.xpandit.xray.model.QueryParameter.TEST_EXEC_KEY, sameTestExecutionKey);
            }

            Map<com.xpandit.xray.model.DataParameter, Content> dataParams = new HashMap<>();

            if (StringUtils.isNotBlank(this.importFilePath)) {
                Content results = new com.xpandit.xray.model.FileStream(resultsFile.getName(), resultsFile.read(),
                                                                        targetEndpoint.getResultsMediaType());
                dataParams.put(com.xpandit.xray.model.DataParameter.FILEPATH, results);

            }

            if (StringUtils.isNotBlank(this.importInfo)) {
                String resolved = expandVariable(env, this.importInfo);

                Content info;
                if (this.inputInfoSwitcher.equals("filePath")) {
                    FilePath infoFile = getFile(workspace, resolved, listener);
                    info = new com.xpandit.xray.model.FileStream(infoFile.getName(),
                                                                 infoFile.read(),
                                                                 targetEndpoint.getInfoFieldMediaType());
                } else {
                    info = new com.xpandit.xray.model.StringContent(resolved, targetEndpoint.getInfoFieldMediaType());
                }

                dataParams.put(com.xpandit.xray.model.DataParameter.INFO, info);
            }

            if (StringUtils.isNotBlank(this.testImportInfo)) {
                String resolvedTestImportInfo = expandVariable(env, this.testImportInfo);

                Content testInfo;
                if (this.inputTestInfoSwitcher.equals("filePath")) {
                    FilePath testInfoFile = getFile(workspace, resolvedTestImportInfo, listener);
                    testInfo = new com.xpandit.xray.model.FileStream(testInfoFile.getName(),
                                                                     testInfoFile.read(),
                                                                     targetEndpoint.getInfoFieldMediaType());
                } else {
                    testInfo = new com.xpandit.xray.model.StringContent(resolvedTestImportInfo,
                                                                        targetEndpoint.getInfoFieldMediaType());
                }

                dataParams.put(com.xpandit.xray.model.DataParameter.TEST_INFO, testInfo);
            }

            listener.getLogger().println("Starting to import results from " + resultsFile.getName());
            UploadResult result = client.uploadResults(targetEndpoint, dataParams, queryParams);
            
            dataParams.values().stream()
                      .map(Content::getContent)
                      .filter(content -> content instanceof AutoCloseable)
                      .map(AutoCloseable.class::cast)
                      .forEach(content -> closeAutoCloseableInstances(listener, (AutoCloseable) content));
            
            listener.getLogger().println("Response: (" + result.getStatusCode() + ") " + result.getMessage());

            if (result.isOkStatusCode()) {
                listener.getLogger()
                        .println("Successfully imported " + targetEndpoint.getName() + " results from " + resultsFile.getName());
            }

            return result;

        } catch (XrayClientCoreGenericException | XrayJenkinsGenericException e) {
            LOG.error(ERROR_LOG, e);
            throw new AbortException(e.getMessage());
        } catch (IOException e) {
            LOG.error(ERROR_LOG, e);
            listener.error(e.getMessage());
            throw new IOException(e);
        }
    }

    private void closeAutoCloseableInstances(TaskListener listener, AutoCloseable autoCloseable) {
        try {
            autoCloseable.close();
        } catch (Exception e) {
            listener.getLogger().println("Error closing autocloseable instance " + e);
        }
    }

    private boolean isMultipartEndpoint(Endpoint endpoint) {
        return endpoint.getName().contains(MULTIPART);
    }

    private Map<com.xpandit.xray.model.QueryParameter, String> prepareQueryParam(EnvVars env) {
        Map<com.xpandit.xray.model.QueryParameter, String> queryParams = new EnumMap<>(QueryParameter.class);
        queryParams.put(com.xpandit.xray.model.QueryParameter.PROJECT_KEY, expandVariable(env, projectKey));
        queryParams.put(com.xpandit.xray.model.QueryParameter.TEST_EXEC_KEY, expandVariable(env, testExecKey));
        queryParams.put(com.xpandit.xray.model.QueryParameter.TEST_PLAN_KEY, expandVariable(env, testPlanKey));
        queryParams.put(com.xpandit.xray.model.QueryParameter.TEST_ENVIRONMENTS, expandVariable(env, testEnvironments));
        queryParams.put(com.xpandit.xray.model.QueryParameter.REVISION, expandVariable(env, revision));
        queryParams.put(com.xpandit.xray.model.QueryParameter.FIX_VERSION, expandVariable(env, fixVersion));
        return queryParams;
    }

    private void validate(Map<String, String> dynamicFields) throws FormValidation {

        if (serverInstance == null) {
            LOG.error("configuration id is null");
            throw new XrayJenkinsGenericException("configuration id is null");
        }
        if (endpointName == null || lookupForEndpoint() == null) {
            LOG.error("passed endpoint is null or could not be found");
            throw new XrayJenkinsGenericException("passed endpoint is null or could not be found");
        }
        if (this.importFilePath == null) {
            LOG.error("importFilePath is null");
            throw new XrayJenkinsGenericException("importFilePath is null");
        }
        for (com.xpandit.xray.model.DataParameter dp : com.xpandit.xray.model.DataParameter.values()) {
            if (dynamicFields.containsKey(dp.getKey()) && dp.isRequired()) {
                String value = dynamicFields.get(dp.getKey());
                if (StringUtils.isBlank(value)) {
                    throw FormValidation.error("You must configure the field " + dp.getLabel());
                }
            }
        }

        for (com.xpandit.xray.model.QueryParameter qp : com.xpandit.xray.model.QueryParameter.values()) {
            if (dynamicFields.containsKey(qp.getKey()) && qp.isRequired()) {
                String value = dynamicFields.get(qp.getKey());
                if (StringUtils.isBlank(value)) {
                    throw FormValidation.error("You must configure the field " + qp.getLabel());
                }
            }
        }

        if (this.importFilePath.contains("../")) {
            throw FormValidation.error("You cannot provide file paths for upper directories.");
        }
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    public String getTestImportInfo() {
        return testImportInfo;
    }

    public void setTestImportInfo(String testImportInfo) {
        this.testImportInfo = testImportInfo;
    }


    @Extension
    public static class Descriptor extends BuildStepDescriptor<Publisher> {
        private static long BUILD_STEP_SEED = 0;
        private long buildID;

        public Descriptor() {
            super(XrayImportBuilder.class);
            load();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return true;
        }

        @Override
        public XrayImportBuilder newInstance(StaplerRequest req, JSONObject formData) throws Descriptor.FormException {
            validateFormData(formData);
            Map<String, String> fields = getDynamicFields(formData.getJSONObject("dynamicFields"));
            String credentialId = Optional.ofNullable(formData.get(CREDENTIAL_ID))
                    .map(Object::toString)
                    .filter(StringUtils::isNotBlank)
                    .orElse(null);

            return new XrayImportBuilder(
                    (String) formData.get(SERVER_INSTANCE),
                    formData.getString(FORMAT_SUFFIX),
                    fields.get(PROJECT_KEY),
                    fields.get(TEST_ENVIRONMENTS),
                    fields.get(TEST_PLAN_KEY),
                    fields.get(FIX_VERSION),
                    fields.get(IMPORT_FILE_PATH),
                    fields.get(TEST_EXEC_KEY),
                    fields.get(REVISION_FIELD),
                    fields.get(IMPORT_INFO),
                    fields.get(TEST_IMPORT_INFO),
                    fields.get(INPUT_INFO_SWITCHER),
                    fields.get(TEST_INFO_INPUT_SWITCHER),
                    fields.get(SAME_EXECUTION_CHECKBOX),
                    credentialId,
                    fields.get(IMPORT_IN_PARALLEL));
        }

        private void validateFormData(JSONObject formData) throws Descriptor.FormException {
            if (StringUtils.isBlank(formData.getString(SERVER_INSTANCE))) {
                throw new Descriptor.FormException(
                        "Xray Results Import Task error, you must provide a valid Jira Instance",
                        SERVER_INSTANCE);
            }
        }

        private Map<String, String> getDynamicFields(JSONObject configuredFields) {

            Map<String, String> dynamicFields = new HashMap<>();

            Set<String> keys = configuredFields.keySet();

            for (String key : keys) {
                if (configuredFields.containsKey(key)) {
                    String value = configuredFields.getString(key);
                    if (StringUtils.isNotBlank(value)) {
                        dynamicFields.put(key, value);
                    }
                }
            }

            return dynamicFields;

        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            LOG.info("applying XrayImportBuilder to following jobType class: {}", jobType.getSimpleName());
            return BuilderUtils.isSupportedJobType(jobType);
        }

        @Override
        public String getDisplayName() {
            return "Xray: Results Import Task";
        }

        public ListBoxModel doFillFormatSuffixItems() {

            ListBoxModel items = new ListBoxModel();
            for (Endpoint e : Endpoint.values()) {
                items.add(e.getName(), e.getSuffix());
            }

            return items;
        }

        public ListBoxModel doFillServerInstanceItems(@AncestorInPath Job job) {
            if (job == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    || job != null && !job.hasPermission(Item.CONFIGURE)) {
                return new ListBoxModel();
            }
            return FormUtils.getServerInstanceItems();
        }

        public ListBoxModel doFillCredentialIdItems(@AncestorInPath Item item,
                                                    @org.kohsuke.stapler.QueryParameter String credentialId) {
            return getUserScopedCredentialsListBoxModel(item, credentialId);
        }

        public FormValidation doCheckCredentialId(@org.kohsuke.stapler.QueryParameter String value, @org.kohsuke.stapler.QueryParameter String serverInstance) {
            final XrayInstance xrayInstance = getConfigurationOrFirstAvailable(serverInstance);
            if (xrayInstance != null && StringUtils.isBlank(xrayInstance.getCredentialId()) && StringUtils.isBlank(value)) {
                return FormValidation.error("This XrayInstance requires an User scoped credential.");
            }
            return FormValidation.ok();
        }

        public long defaultBuildID() {
            return buildID;
        }

        public void setBuildID() {
            buildID = ++BUILD_STEP_SEED;
        }

        public String defaultFormats() {
            Map<String, FormatBean> formats = new HashMap<>();
            for (Endpoint e : Endpoint.values()) {
                FormatBean bean = e.toBean();
                addImportToSameExecField(e, bean);
                addImportInParallel(bean);
                formats.put(e.getSuffix(), bean);
            }
            return gson.toJson(formats);
        }

        private void addImportToSameExecField(Endpoint e, FormatBean bean) {
            if (BuilderUtils.isGlobExpressionsSupported(e)) {
                ParameterBean pb = new ParameterBean(SAME_EXECUTION_CHECKBOX, "same exec text box", false);
                bean.getConfigurableFields().add(0, pb);
            }
        }

        private void addImportInParallel(FormatBean bean) {
            ParameterBean pb = new ParameterBean(IMPORT_IN_PARALLEL, "import in parallel text box", false);
            bean.getConfigurableFields().add(pb);
        }

        public List<XrayInstance> getServerInstances() {
            return ServerConfiguration.get().getServerInstances();
        }


        public FormValidation doCheckServerInstance(@AncestorInPath Item item) {
            if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                return FormValidation.ok();
            }
            return ConfigurationUtils.anyAvailableConfiguration() ?
                    FormValidation.ok() :
                    FormValidation.error("No configured Server Instances.");
        }

        public String getCloudHostingTypeName() {
            return HostingType.getCloudHostingName();
        }

        public String getServerHostingTypeName() {
            return HostingType.getServerHostingName();
        }

        public JSONObject getExclusiveCloudEndpoints() {
            String[] exclusiveCloudEndpoints = Endpoint.getExclusiveCloudEndpoints();
            JSONObject jsonExclusiveCloudEndpoints = new JSONObject();

            for (String suffix : exclusiveCloudEndpoints) {
                jsonExclusiveCloudEndpoints.put(suffix, suffix);
            }

            return jsonExclusiveCloudEndpoints;
        }

        public JSONObject getExclusiveServerEndpoints() {
            String[] exclusiveServerEndpoints = Endpoint.getExclusiveServerEndpoints();
            JSONObject jsonExclusiveServerEndpoints = new JSONObject();

            for (String suffix : exclusiveServerEndpoints) {
                jsonExclusiveServerEndpoints.put(suffix, suffix);
            }

            return jsonExclusiveServerEndpoints;
        }

        public String getCloudDocUrl() {
            return CLOUD_DOC_URL;
        }

        public String getServerDocUrl() {
            return SERVER_DOC_URL;
        }
    }

}
