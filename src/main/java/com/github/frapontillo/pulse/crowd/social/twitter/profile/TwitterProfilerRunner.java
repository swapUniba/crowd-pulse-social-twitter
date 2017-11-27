package com.github.frapontillo.pulse.crowd.social.twitter.profile;

import com.github.frapontillo.pulse.crowd.data.entity.Profile;
import com.github.frapontillo.pulse.crowd.social.profile.ProfileParameters;
import com.github.frapontillo.pulse.crowd.social.twitter.TwitterFactory;
import com.github.frapontillo.pulse.util.PulseLogger;
import twitter4j.ResponseList;
import twitter4j.TwitterException;
import twitter4j.User;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Francesco Pontillo
 */
public class TwitterProfilerRunner {
    private static final org.apache.logging.log4j.Logger logger =
            PulseLogger.getLogger(TwitterProfilerRunner.class);

    public List<Profile> getProfiles(ProfileParameters parameters) {
        List<Profile> profiles = new ArrayList<>();
        boolean mustRepeat = false;
        do {
            try {
                String[] usernames = new String[parameters.getProfiles().size()];
                usernames = parameters.getProfiles().toArray(usernames);
                ResponseList<User> users =
                        TwitterFactory.getTwitterInstance().lookupUsers(usernames);
                users.stream().forEach(u -> profiles
                        .add(new TwitterProfileConverter(parameters).fromExtractor(u, null)));
                mustRepeat = false;
            } catch (TwitterException twitterException) {
                try {
                    mustRepeat = TwitterFactory.waitForTwitterTimeout(twitterException, logger);
                } catch (InterruptedException ignored) {
                }
                // if the error isn't handled, just ignore it and return an empty list
                // e.g. a query for non-existing users may return 404, we don't care LOL
            }
        } while (mustRepeat);
        return profiles;
    }
}
