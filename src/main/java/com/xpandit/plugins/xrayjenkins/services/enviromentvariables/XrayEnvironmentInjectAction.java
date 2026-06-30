package com.xpandit.plugins.xrayjenkins.services.enviromentvariables;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.EnvironmentContributingAction;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This class implements a Jenkins interface: EnvironmentContributingAction, ans as the name suggests, this class
 * will be called by Jenkins itself to change/set new environment variables into the current build.
 * Beware that this class will NOT be called in a Pipeline project, due to Jenkins limitations.
 */
public class XrayEnvironmentInjectAction implements EnvironmentContributingAction, Serializable {

    private final Map<String, String> newVariablesToAdd;

    public XrayEnvironmentInjectAction(@Nonnull Map<String, String> variablesToAdd) {
        Objects.requireNonNull(variablesToAdd, "'variablesToAdd' can't be null!");

        // We can't use Java native Synchronized structures since they are blocked by Jenkins in a Pipeline project
        // See: https://jenkins.io/blog/2018/01/13/jep-200/
        this.newVariablesToAdd = Collections.synchronizedMap(new HashMap<>(variablesToAdd));
    }

    @Override
    public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
        env.putAll(newVariablesToAdd);
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return null;
    }

    public Map<String, String> getXrayEnvVarsResult() {
        return newVariablesToAdd;
    }
}
