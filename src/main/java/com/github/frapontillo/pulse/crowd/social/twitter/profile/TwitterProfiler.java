package com.github.frapontillo.pulse.crowd.social.twitter.profile;

import com.github.frapontillo.pulse.crowd.data.entity.Profile;
import com.github.frapontillo.pulse.crowd.social.profile.IProfiler;
import com.github.frapontillo.pulse.crowd.social.profile.ProfileParameters;
import com.github.frapontillo.pulse.crowd.social.profile.ProfilerException;

import java.util.List;

/**
 * @author Francesco Pontillo
 */
public class TwitterProfiler extends IProfiler {
    public static final String PLUGIN_NAME = "profiler-twitter";
    private static TwitterProfilerRunner runner = null;

    @Override public String getName() {
        return PLUGIN_NAME;
    }

    private TwitterProfilerRunner getRunnerInstance() {
        if (runner == null) {
            runner = new TwitterProfilerRunner();
        }
        return runner;
    }

    @Override public List<Profile> getProfiles(ProfileParameters parameters)
            throws ProfilerException {
        return getRunnerInstance().getProfiles(parameters);
    }
}
