package com.github.frapontillo.pulse.crowd.social.twitter.extraction;

import com.github.frapontillo.pulse.crowd.data.entity.Message;
import com.github.frapontillo.pulse.crowd.social.extraction.ExtractionParameters;
import com.github.frapontillo.pulse.crowd.social.extraction.IReplyExtractor;
import com.github.frapontillo.pulse.crowd.social.twitter.TwitterFactory;
import com.github.frapontillo.pulse.util.PulseLogger;
import twitter4j.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Francesco Pontillo
 */
public class TwitterReplyExtractor extends IReplyExtractor {
    public final static String PLUGIN_NAME = "reply-extractor-twitter";
    private static final org.apache.logging.log4j.Logger logger =
            PulseLogger.getLogger(TwitterReplyExtractor.class);

    @Override public List<Message> getReplies(Message message, ExtractionParameters parameters) {
        List<Message> messages = new ArrayList<>();
        TwitterMessageConverter converter = new TwitterMessageConverter(parameters);
        HashMap<String, Object> map = new HashMap<>();
        map.put(TwitterMessageConverter.DATA_REPLY_TO_COMMENT, message.getoId());
        map.put(TwitterMessageConverter.DATA_REPLY_TO_USER, message.getFromUser());
        map.put(TwitterMessageConverter.DATA_SOURCE, parameters.getSource());
        Query query = buildQuery(message);
        try {
            Twitter twitter = com.github.frapontillo.pulse.crowd.social.twitter.TwitterFactory
                    .getTwitterInstance();
            do {
                QueryResult result = null;
                try {
                    result = twitter.search(query);
                } catch (TwitterException timeout) {
                    if (TwitterFactory.waitForTwitterTimeout(timeout, logger)) {
                        continue;
                    }
                }
                List<Status> statuses = result.getTweets();
                // filter the statuses by returning only the ones in reply to the current message
                statuses = statuses.stream().filter(status -> status.getInReplyToStatusId() ==
                        Long.parseLong(message.getoId())).collect(Collectors.toList());
                converter.addFromExtractor(statuses, messages, map);
                query = result.nextQuery();
            } while (query != null);
        } catch (TwitterException | InterruptedException e) {
            e.printStackTrace();
        }
        return messages;
    }

    /**
     * Build the {@link Query} for the {@link Message} search.
     * The results will consist in every {@link Message} addressed to the author since the {@link
     * Message} was posted.
     * {@link Message}s must then be manually filtered in order to include only the ones in reply
     * to
     * the original {@link Message}.
     *
     * @param message The {@link Message} to retrieve replies for.
     *
     * @return A Twitter {@link Query} to fetch a superset of replies to the input {@link Message}.
     */
    private Query buildQuery(Message message) {
        Query query = new Query();
        query.setQuery("to:" + message.getFromUser());
        query.setSinceId(Long.parseLong(message.getoId()));
        return query;
    }

    @Override public String getName() {
        return PLUGIN_NAME;
    }
}
