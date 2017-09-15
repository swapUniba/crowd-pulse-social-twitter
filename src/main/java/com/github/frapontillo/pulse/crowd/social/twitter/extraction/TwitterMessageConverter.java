package com.github.frapontillo.pulse.crowd.social.twitter.extraction;

import com.github.frapontillo.pulse.crowd.data.entity.Message;
import com.github.frapontillo.pulse.crowd.social.extraction.ExtractionParameters;
import com.github.frapontillo.pulse.crowd.social.extraction.MessageConverter;
import com.github.frapontillo.pulse.util.StringUtil;
import twitter4j.Status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * @author Francesco Pontillo
 */
public class TwitterMessageConverter extends MessageConverter<Status> {

    public TwitterMessageConverter(ExtractionParameters parameters) {
        super(parameters);
    }

    @Override
    public Message fromSpecificExtractor(Status original, HashMap<String, Object> additionalData) {
        Message message = new Message();
        message.setoId(Long.toString(original.getId()));
        message.setText(original.getText());
        message.setFromUser(original.getUser().getScreenName());

        // if the current message is a comment to another message, set its parent here
        if (additionalData != null) {
            String parent = (String) additionalData.get(DATA_REPLY_TO_COMMENT);
            if (parent != null) {
                message.setParent(parent);
            }
        }

        if (!StringUtil.isNullOrEmpty(original.getInReplyToScreenName())) {
            List<String> toIds = new ArrayList<>();
            toIds.add(original.getInReplyToScreenName());
            message.setToUsers(toIds);
        }
        message.setDate(original.getCreatedAt());
        if (original.getGeoLocation() != null) {
            message.setLatitude(original.getGeoLocation().getLatitude());
            message.setLongitude(original.getGeoLocation().getLongitude());
        }
        message.setLanguage(original.getLang());
        message.setFavs(original.getFavoriteCount());
        message.setShares(original.getRetweetCount());

        return message;
    }
}
