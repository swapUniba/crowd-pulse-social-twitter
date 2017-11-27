package com.github.frapontillo.pulse.crowd.social.twitter.profile;

import com.github.frapontillo.pulse.crowd.data.entity.Profile;
import com.github.frapontillo.pulse.crowd.social.profile.ProfileConverter;
import com.github.frapontillo.pulse.crowd.social.profile.ProfileParameters;
import twitter4j.User;

import java.util.HashMap;

/**
 * @author Francesco Pontillo
 */
public class TwitterProfileConverter extends ProfileConverter<User> {

    public TwitterProfileConverter(ProfileParameters parameters) {
        super(parameters);
    }

    @Override
    protected Profile fromSpecificExtractor(User original, HashMap<String, Object> additionalData) {
        Profile profile = new Profile();
        profile.setUsername(original.getScreenName());
        profile.setActivationDate(original.getCreatedAt());
        profile.setFollowings(original.getFriendsCount());
        profile.setFollowers(original.getFollowersCount());
        profile.setLanguage(original.getLang());
        profile.setLocation(original.getLocation());
        // latitude and longitude are not directly exposed by Twitter
        return profile;
    }

}
