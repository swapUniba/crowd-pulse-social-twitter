crowd-pulse-social-twitter
==========================

Twitter social extractor implementation for Crowd Pulse.

--------------------------

This module contains several plugins (`extractor-twitter`, `reply-extractor-twitter`, 
`profiler-twitter`, `twitter-profile-grapher`) that need a `twitter4j.properties` file in the class 
loader accessible resources directory.

It must hold the following keys and related values:

- `twitter.consumerKey`, your Twitter consumer key
- `twitter.consumerSecret`, your Twitter consumer secret
- `twitter.accessToken`, your Twitter access token
- `twitter.accessTokenSecret`, your Twitter access token secret