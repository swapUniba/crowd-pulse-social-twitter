package com.github.frapontillo.pulse.crowd.social.twitter.extraction;

import com.github.frapontillo.pulse.crowd.data.entity.Message;
import com.github.frapontillo.pulse.crowd.social.extraction.ExtractionParameters;
import com.github.frapontillo.pulse.crowd.social.twitter.TwitterFactory;
import com.github.frapontillo.pulse.crowd.social.util.Checker;
import com.github.frapontillo.pulse.util.DateUtil;
import com.github.frapontillo.pulse.util.PulseLogger;
import com.github.frapontillo.pulse.util.StringUtil;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;
import rx.observers.SafeSubscriber;
import rx.schedulers.Schedulers;
import twitter4j.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Francesco Pontillo
 */
public class TwitterExtractorRunner {

    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private static final int TWEETS_PER_PAGE = 200;
    private static final org.apache.logging.log4j.Logger logger =
            PulseLogger.getLogger(TwitterExtractorRunner.class);

    public Observable<Message> getMessages(final ExtractionParameters parameters) {

        // initialize the twitter instances
        try {
            getTwitterInstance();
            getTwitterStreamInstance();
        } catch (TwitterException e) {
            logger.error("Can't instantiate a Twitter instance", e);
        }

        // create the old messages Observable
        Observable<Message> oldMessages;
        if (StringUtil.isNullOrEmpty(parameters.getFrom())) {
            // if the generating user is not specified, we need to use the Search API
            // which are very limited in the time span they can search (6-9 days top)
            oldMessages = Observable.create(new Observable.OnSubscribe<Message>() {
                @Override public void call(Subscriber<? super Message> subscriber) {
                    logger.info("SEARCH: started.");
                    getOldMessagesBySearch(parameters, subscriber);
                }
            }).onBackpressureBuffer();
        } else {
            // otherwise, we can use the author's timeline, but we have to manually filter on:
            // query, location, to user, referenced users, since, until, language, locale
            oldMessages = Observable.create(new Observable.OnSubscribe<Message>() {
                @Override public void call(Subscriber<? super Message> subscriber) {
                    logger.info("SEARCH: started.");
                    getOldMessagesByTimeline(parameters, subscriber);
                }
            }).onBackpressureBuffer();
        }

        // create the new messages (streamed) Observable
        Observable<Message> newMessages = Observable.create(new Observable.OnSubscribe<Message>() {
            @Override public void call(Subscriber<? super Message> subscriber) {
                logger.info("STREAMING: started.");
                getNewMessages(parameters, subscriber);
            }
        }).onBackpressureBuffer();
        // filter the new messages to properly match the extraction parameters
        newMessages = newMessages.filter(Checker.checkFromUser(parameters))
                .filter(Checker.checkToUser(parameters)).filter(checkReferencedUsers(parameters))
                .filter(Checker.checkUntilDate(parameters))
                // continue producing elements until the target date is reached
                .takeUntil(timeToWait(parameters));

        // both observables should execute the subscribe function in separate threads
        oldMessages = oldMessages.subscribeOn(Schedulers.io());
        newMessages = newMessages.subscribeOn(Schedulers.io());

        // the resulting Observable should be a union of both old and new messages
        // make it as a ConnectableObservable so that multiple subscribers can subscribe to it
        Observable<Message> messages = Observable.merge(oldMessages, newMessages);
        messages = messages.lift(subscriber -> new SafeSubscriber<>(new Subscriber<Message>() {
            @Override public void onCompleted() {
                cleanup();
                subscriber.onCompleted();
            }

            @Override public void onError(Throwable e) {
                cleanup();
                subscriber.onError(e);
            }

            @Override public void onNext(Message message) {
                subscriber.onNext(message);
            }

            private void cleanup() {
                try {
                    getTwitterStreamInstance().cleanUp();
                    getTwitterStreamInstance().shutdown();
                    logger.info("SEARCH: ended.");
                    logger.info("STREAMING: ended.");
                } catch (TwitterException e) {
                    e.printStackTrace();
                }
            }
        }));

        return messages;
    }

    /**
     * Get the appropriate {@link rx.Observable} that will either complete after a certain amount
     * of time (depending on the input {@link ExtractionParameters#getUntil()}) or never complete.
     *
     * @param parameters The parameters, as set by the user, that can contain a null or valid
     *                   "until date".
     *
     * @return {@link rx.Observable} that can complete after some time, or will never complete.
     */
    private Observable<Long> timeToWait(ExtractionParameters parameters) {
        if (parameters.getUntil() != null) {
            long timeToDeath = parameters.getUntil().getTime() - new Date().getTime();
            logger.info("Shutting down the Streaming service in {} seconds.", timeToDeath);
            return Observable.timer(timeToDeath, TimeUnit.MILLISECONDS, Schedulers.io());
        }
        return Observable.never();
    }

    /**
     * Download all old messages (maximum 7-10 days past) that match the {@link
     * ExtractionParameters}.
     *
     * @param parameters The {@link ExtractionParameters} with all extraction settings.
     * @param subscriber The {@link rx.Subscriber} that will be notified of new tweets, errors and
     *                   completion.
     */
    private void getOldMessagesBySearch(ExtractionParameters parameters,
            Subscriber<? super Message> subscriber) {
        try {
            Twitter twitter = getTwitterInstance();
            Query query = buildQuery(parameters);
            TwitterMessageConverter converter = new TwitterMessageConverter(parameters);
            // query can be null if we reach the end of the search result pages
            while (query != null) {
                // get the tweets and convert them
                QueryResult result;
                try {
                    result = twitter.search(query);
                } catch (TwitterException timeout) {
                    boolean canRepeat =
                            com.github.frapontillo.pulse.crowd.social.twitter.TwitterFactory
                                    .waitForTwitterTimeout(timeout, logger);
                    if (canRepeat) {
                        continue;
                    }
                    throw timeout;
                }
                List<Status> tweetList = result.getTweets();
                List<Message> messageList = converter.fromExtractor(tweetList);
                // notify the subscriber of new tweets
                messageList.forEach(subscriber::onNext);
                // since the Twitter API is severely broken (https://gist.github
                // .com/frapontillo/724b02cd1a3098eba96b)
                // we can't use result.nextQuery() as it may be null
                // to build the next query simply set the max_id parameter to the last fetched
                // tweet ID minus 1
                int size = tweetList.size();
                if (size > 0) {
                    long lastId = tweetList.get(size - 1).getId();
                    query.setMaxId(lastId - 1);
                } else {
                    query = null;
                }
            }
            // at this point, there is no other available query: we have finished
            subscriber.onCompleted();
        } catch (TwitterException | InterruptedException e) {
            subscriber.onError(e);
        }

    }

    /**
     * Download all old messages (maximum 3200) that match the {@link ExtractionParameters} from
     * the
     * author user's timeline.
     * For this reason, call this method only if the "from" user is defined.
     *
     * @param parameters The {@link ExtractionParameters} with all extraction settings.
     * @param subscriber The {@link rx.Subscriber} that will be notified of new tweets, errors and
     *                   completion.
     */
    private void getOldMessagesByTimeline(ExtractionParameters parameters,
            Subscriber<? super Message> subscriber) {
        try {
            Twitter twitter = getTwitterInstance();
            Paging paging = new Paging().count(TWEETS_PER_PAGE);
            TwitterMessageConverter converter = new TwitterMessageConverter(parameters);
            // query can be null if we reach the end of the search result pages
            while (paging != null) {
                // get the tweets
                ResponseList<Status> tweetList = null;
                try {
                    tweetList = twitter.getUserTimeline(parameters.getFrom(), paging);
                } catch (TwitterException timeout) {
                    if (TwitterFactory.waitForTwitterTimeout(timeout, logger)) {
                        continue;
                    }
                }

                long maxId = -1;
                // if there are no tweets, we reached the end of the search, otherwise get the
                // latest ID
                if (tweetList != null && !tweetList.isEmpty()) {
                    maxId = tweetList.get(tweetList.size() - 1).getId();
                } else {
                    paging = null;
                }

                // convert the whole list
                List<Message> messageList = converter.fromExtractor(tweetList);
                // notify the subscriber of new tweets
                for (Message message : messageList) {
                    if (Checker.checkQuery(parameters).call(message) &&
                            Checker.checkLocation(parameters).call(message) &&
                            Checker.checkFromUser(parameters).call(message) &&
                            Checker.checkToUser(parameters).call(message) &&
                            Checker.checkReferencedUsers(parameters).call(message) &&
                            Checker.checkSinceDate(parameters).call(message) &&
                            Checker.checkUntilDate(parameters).call(message) &&
                            Checker.checkLanguage(parameters).call(message)) {
                        subscriber.onNext(message);
                    }
                    // since tweets are in inverse chronological order, if the current tweet is
                    // already past the
                    // "since" threshold, all following tweets are too, so we can exit the loop
                    // and complete
                    if (!Checker.checkSinceDate(parameters).call(message)) {
                        paging = null;
                        break;
                    }
                }

                // get the next page
                if (paging != null) {
                    paging.setMaxId(maxId - 1);
                }
            }
            // at this point, there is no other available query: we have finished
            subscriber.onCompleted();
        } catch (InterruptedException | TwitterException e) {
            subscriber.onError(e);
        }
    }

    /**
     * Open up a stream to Twitter, listening to new tweets according to the {@link
     * ExtractionParameters}.
     *
     * @param parameters The {@link ExtractionParameters} with all extraction settings.
     * @param subscriber The {@link rx.Subscriber} that will be notified of new tweets, errors and
     *                   completion.
     */
    private void getNewMessages(ExtractionParameters parameters,
            final Subscriber<? super Message> subscriber) {
        if (parameters.getUntil() == null ||
                (parameters.getUntil().getTime() - new Date().getTime() <= 0)) {
            subscriber.onCompleted();
            return;
        }
        try {
            TwitterStream twitterStream = getTwitterStreamInstance();
            final TwitterMessageConverter converter = new TwitterMessageConverter(parameters);
            twitterStream.addListener(new StatusListener() {
                @Override public void onStatus(Status status) {
                    subscriber.onNext(converter.fromExtractor(status));
                }

                @Override public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {
                    // ignore
                }

                @Override public void onTrackLimitationNotice(int numberOfLimitedStatuses) {
                    // ignore
                }

                @Override public void onScrubGeo(long userId, long upToStatusId) {
                    // ignore
                }

                @Override public void onStallWarning(StallWarning warning) {
                    logger.error(warning.toString());
                    System.err.println(warning);
                }

                @Override public void onException(Exception ex) {
                    subscriber.onError(ex);
                }
            });
            FilterQuery filterQuery = buildFilterQuery(parameters);
            twitterStream.filter(filterQuery);
        } catch (TwitterException e) {
            subscriber.onError(e);
        }
    }

    /**
     * Returns a singleton instance of the {@link twitter4j.Twitter} client.
     *
     * @return A set up and ready {@link twitter4j.Twitter} client.
     * @throws TwitterException if the client could not be built.
     */
    private Twitter getTwitterInstance() throws TwitterException {
        return TwitterFactory.getTwitterInstance();
    }

    /**
     * Returns a singleton instance of the {@link twitter4j.Twitter} client.
     *
     * @return A set up and ready {@link twitter4j.TwitterStream} client.
     * @throws TwitterException if the client could not be built.
     */
    private TwitterStream getTwitterStreamInstance() throws TwitterException {
        return TwitterFactory.getTwitterStreamInstance();
    }

    /**
     * Creates a {@link twitter4j.Query} object from some {@link ExtractionParameters}.
     *
     * @param parameters The source-independent search parameters.
     *
     * @return A {@link twitter4j.Query} Twitter object.
     */
    private Query buildQuery(ExtractionParameters parameters) {
        Query query = new Query();

        if (parameters.getGeoLocationBox() != null) {
            query.setGeoCode(new GeoLocation(parameters.getGeoLocationBox().getLatitude(),
                            parameters.getGeoLocationBox().getLongitude()),
                    parameters.getGeoLocationBox().getDistance(), Query.Unit.km);
        }
        if (!StringUtil.isNullOrEmpty(parameters.getLanguage())) {
            query.setLang(parameters.getLanguage());
        }
        if (!StringUtil.isNullOrEmpty(parameters.getLocale())) {
            query.setLocale(parameters.getLocale());
        }
        if (parameters.getSince() != null) {
            query.setSince(DateUtil.toString(parameters.getSince(), DATE_FORMAT));
        }
        if (parameters.getUntil() != null) {
            query.setUntil(DateUtil.toString(parameters.getUntil(), DATE_FORMAT));
        }

        // start building the main query text
        StringBuilder queryStringBuilder = new StringBuilder();
        if (!StringUtil.isNullOrEmpty(parameters.getFrom())) {
            queryStringBuilder.append("from:").append(parameters.getFrom()).append(" ");
        }
        if (!StringUtil.isNullOrEmpty(parameters.getTo())) {
            queryStringBuilder.append("to:").append(parameters.getTo()).append(" ");
        }
        if (parameters.getReferences() != null && parameters.getReferences().size() > 0) {
            parameters.getReferences().stream().filter(user -> !StringUtil.isNullOrEmpty(user))
                    .forEach(user -> queryStringBuilder.append("@").append(user).append(" "));
        }
        List<String> components = parameters.getQuery();
        if (components != null) {
            for (int i = 0; i < components.size(); i++) {
                queryStringBuilder.append(components.get(i));
                if (i < components.size() - 1) {
                    queryStringBuilder.append(" OR ");
                }
            }
        }

        query.setQuery(queryStringBuilder.toString());
        query.setResultType(Query.ResultType.recent);
        query.setCount(TWEETS_PER_PAGE);

        return query;
    }

    /**
     * Creates a {@link twitter4j.FilterQuery} object from some {@link ExtractionParameters}.
     *
     * @param parameters The source-independent search parameters.
     *
     * @return A {@link twitter4j.FilterQuery} Twitter object.
     * @throws TwitterException if the query could not be built because of some issues
     *                          instantiating
     *                          the regular client.
     */
    private FilterQuery buildFilterQuery(ExtractionParameters parameters) throws TwitterException {
        FilterQuery filterQuery = new FilterQuery();

        // build the location parameters
        if (parameters.getGeoLocationBox() != null) {
            filterQuery.locations(parameters.getGeoLocationBox().getBoundingBox());
        }

        // set the language
        if (!StringUtil.isNullOrEmpty(parameters.getLanguage())) {
            filterQuery.language(parameters.getLanguage());
        }

        // set the users to retrieve tweets from
        List<String> users = new ArrayList<>();
        if (!StringUtil.isNullOrEmpty(parameters.getFrom())) {
            users.add(parameters.getFrom());
        }
        if (!StringUtil.isNullOrEmpty(parameters.getTo())) {
            users.add(parameters.getTo());
        }
        if (parameters.getReferences() != null && parameters.getReferences().size() > 0) {
            users.addAll(parameters.getReferences().stream()
                    .filter(user -> !StringUtil.isNullOrEmpty(user)).collect(Collectors.toList()));

            // convert user names into user IDs (long) using the Twitter API
            ResponseList<User> twitterUsers = getTwitterInstance().users()
                    .lookupUsers(users.toArray(new String[users.size()]));
            long[] twitterUserIds = new long[twitterUsers.size()];
            int i = 0;
            for (User user : twitterUsers) {
                twitterUserIds[i] = user.getId();
                i += 1;
            }
            filterQuery.follow(twitterUserIds);
        }

        // set the terms to search for
        if (parameters.getQuery() != null) {
            String[] parametersArray = new String[parameters.getQuery().size()];
            parametersArray = parameters.getQuery().toArray(parametersArray);
            filterQuery.track(parametersArray);
        }

        return filterQuery;
    }

    private Func1<Message, Boolean> checkReferencedUsers(final ExtractionParameters parameters) {
        return message -> {
            // if no referenced users are requested
            if (parameters.getReferences() == null || parameters.getReferences().size() <= 0) {
                return true;
            }
            // if some referenced users are requested, all tweets should contain those
            // BUT we need to exclude all tweets whose recipient user matches a referenced user
            // (referenced users are all users involved in a tweet but the recipient)
            return !parameters.getReferences().contains(message.getToUsers());
        };
    }

}
