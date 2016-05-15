package com.valkryst.dds.manager;

import com.google.common.collect.ArrayListMultimap;
import com.valkryst.dds.collection.SplayTree;
import com.valkryst.dds.object.*;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DDSManager implements Serializable {
    private static final long serialVersionUID = 6158503022877874004L;

    /** The Random to use where necessary. */
    private final Random random = new Random(System.nanoTime());
    /** The Publisher to use when handling Responses. */
    private final Publisher publisher = new Publisher();


    /** The ConcurrentHashMap containing User IDs and the Users that they corrospond to. */
    private ConcurrentHashMap<Long, User> hashMap_users = new ConcurrentHashMap<>();

    /** The Events that can be used by the Dynamic Dialog System. */
    private ArrayList<String> arrayList_events;

    /** The ResponseTypes that can be used by the Dynamic Dialog System. */
    private ArrayList<String> arrayList_responseTypes;



    /** The ArrayList containing all Criterion, with their IDs as Keys. */
    private ArrayList<Criterion> arrayList_criterion = new ArrayList<>();
    /** The ArrayList containing all Responses, with their IDs as Keys. */
    private ArrayList<Response> arrayList_response = new ArrayList<>();
    /** The ArrayList containing all Rules, with their IDs as Keys. */
    private ArrayList<Rule> arrayList_rules = new ArrayList<>();

    /** The ArrayList containing all possible Context names. */
    private ArrayList<String> arrayList_contextNames = new ArrayList<>();

    /** The ArrayListMultimap containing all associations between each Event and the Rules that it triggers. */
    private ArrayListMultimap<String, Rule> arrayListMultimap_ruleEventAssociations = ArrayListMultimap.create();
    /** The ArrayListMultimap containing all associations between each Rule and it's Responses. */
    private ArrayListMultimap<Rule, Response> arrayListMultimap_ruleResponseAssociations = ArrayListMultimap.create();
    /** The ArrayListMultimap containing all associations between each Rule and it's Criterion. */
    private ArrayListMultimap<Rule, Criterion> arrayListMultimap_ruleCriterionAssociations = ArrayListMultimap.create();

    /** The ConcurrentHashMap containing Context Namess and the time at which they were last used. */
    private ConcurrentHashMap<Context, Long> hashMap_context_lastUsedTime =  new ConcurrentHashMap<>();
    /** The ConcurrentHashMap containing Criterion IDs and the time at which they were last used. */
    private ConcurrentHashMap<Criterion, Long> hashMap_criterion_lastUsedTime =  new ConcurrentHashMap<>();
    /** The ConcurrentHashMap containing Response IDs and the time at which they were last used. */
    private ConcurrentHashMap<Response, Long> hashMap_response_lastUsedTime =  new ConcurrentHashMap<>();
    /** The ConcurrentHashMap containing Rule IDs and the time at which they were last used. */
    private ConcurrentHashMap<Rule, Long> hashMap_rules_lastUsedTime = new ConcurrentHashMap<>();


    /** The Lock of the arrayList_events data structure. */
    private final ReentrantReadWriteLock lock_arrayList_events = new ReentrantReadWriteLock();
    /** The Lock of the arrayList_responseTypes data structure. */
    private final ReentrantReadWriteLock lock_arrayList_responseTypes = new ReentrantReadWriteLock();
    /** The Lock of the arrayList_criterion data structure. */
    private final ReentrantReadWriteLock lock_arrayList_criterion = new ReentrantReadWriteLock();
    /** The Lock of the arrayList_response data structure. */
    private final ReentrantReadWriteLock lock_arrayList_responses = new ReentrantReadWriteLock();
    /** The Lock of the arrayList_rules data structure. */
    private final ReentrantReadWriteLock lock_arrayList_rules = new ReentrantReadWriteLock();
    /** The Lock of the arrayList_contextNames data structure. */
    private final ReentrantReadWriteLock lock_arrayList_contextNames = new ReentrantReadWriteLock();
    /** The Lock of the arrayListMultimap_ruleEventAssociations data structure. */
    private final ReentrantReadWriteLock lock_arrayListMultimap_ruleEventAssociations = new ReentrantReadWriteLock();
    /** The Lock of the arrayListMultimap_ruleResponseAssociations data structure. */
    private final ReentrantReadWriteLock lock_arrayListMultimap_ruleResponseAssociations = new ReentrantReadWriteLock();
    /** The Lock of the arrayListMultimap_ruleCriterionAssociations data structure. */
    private final ReentrantReadWriteLock lock_arrayListMultimap_ruleCriterionAssociations = new ReentrantReadWriteLock();

    /**
     * Construct a new DDSManager.
     *
     * @param arrayList_events
     *         The Events that can be used by the Dynamic Dialog System.
     *
     * @param arrayList_responseTypes
     *         The ResponseTypes that can be used by the Dynamic Dialog system.
     */
    public DDSManager(final ArrayList<String> arrayList_events, final ArrayList<String> arrayList_responseTypes) {
        this.arrayList_events = arrayList_events;
        this.arrayList_responseTypes = arrayList_responseTypes;
    }


    /**
     * Updates all of the Criterions associated with the specified Rule.
     *
     * @param rule
     *         The Rule whose associated Criterions are to be updated.
     *
     * @return
     *         The total number of Criterions who, after being updated, evaluate
     *         to TRUE.
     */
    private int updateRuleCriterion(final Rule rule) {
        int isTrueCounter = 0;

        // Write Lock the Data:
        lock_arrayListMultimap_ruleCriterionAssociations.writeLock().lock();

        // Update the Data:
        for(final Criterion criterion : arrayListMultimap_ruleCriterionAssociations.get(rule)) {
            criterion.update();

            isTrueCounter += (criterion.getIsTrue() ? 1 : 0);
        }

        // Unlock the Lock.
        lock_arrayListMultimap_ruleCriterionAssociations.writeLock().unlock();

        return isTrueCounter;
    }

    public void determineResponse(final String event) {
        // Determine the Triggered Rules and their Scores:
        final List<Rule> set_triggeredRules = arrayListMultimap_ruleEventAssociations.get(event);
        final ConcurrentHashMap<Rule, Double> hashMap_scores = new ConcurrentHashMap<>();

        set_triggeredRules.parallelStream()
                .forEachOrdered(rule -> {
                    updateRuleCriterion(rule);
                    hashMap_scores.put(rule, determineCriterionWeight(rule));
                });


        final int totalScoredRules = set_triggeredRules.size();

        if (totalScoredRules == 0) { // If no Rules were found
            return;
        } else if (totalScoredRules == 1) { // If one Rule was found
            determineResponseCaseB(set_triggeredRules);

        } else if (set_triggeredRules.parallelStream().anyMatch(rule -> rule.getLastUsedTime() == 0)) { // Multiple rules found, some not used before
            determineResponseCaseC(set_triggeredRules, hashMap_scores);

        } else if(set_triggeredRules.parallelStream().allMatch(r -> arrayListMultimap_ruleCriterionAssociations.get(r).size() == 0)) { // Multiple rules found, none have Criterion
            determineResponseCaseD(set_triggeredRules);

        } else {
            determineResponseCaseE(set_triggeredRules, hashMap_scores);
        }
    }

    /**
     * If only one Rule was found in the set of triggered rules,
     * then respond to it.
     *
     * @param list_triggeredRules
     *         The Rules of which one will be responded to.
     */
    private void determineResponseCaseB(final List<Rule> list_triggeredRules) {
        // If only one Rule is found, then respond to it.
        list_triggeredRules.get(0).updateLastUsedTime();
        publisher.publishResponses(this, arrayListMultimap_ruleResponseAssociations.get(list_triggeredRules.get(0)));
    }

    /**
     * If multiple Rules were found in the set of triggered rules,
     * then the Rule with the highest Criterion weight and which has
     * never been run before will be used.
     *
     * @param list_triggeredRules
     *         The Rules of which one will be responded to.
     */
    private void determineResponseCaseC(final List<Rule> list_triggeredRules, final ConcurrentHashMap<Rule, Double> hashMap_scores) {
        double highestWeight = 0;
        double currentWeight = 0;

        Rule ruleWithHighestWeight = null;

        for(final Rule rule : list_triggeredRules) {
            currentWeight = hashMap_scores.get(rule);

            if(currentWeight > highestWeight) {
                highestWeight = currentWeight;
                ruleWithHighestWeight = rule;
            }
        }



        if(ruleWithHighestWeight == null) {
            throw new IllegalStateException("The algorithm to determine which Rule to use, when there exists " +
                    " a Rule that has not been run before, has not chosen a Rule to " +
                    "respond to.");
        } else {
            ruleWithHighestWeight.updateLastUsedTime();
            publisher.publishResponses(this, arrayListMultimap_ruleResponseAssociations.get(ruleWithHighestWeight));
        }
    }

    /**
     * If multiple Rules were found in the set of triggered rules
     * and none of them have any associated Criterion, then the
     * Rule that was least recently used will be used.
     *
     * @param list_triggeredRules
     *         The Rules of which one will be responded to.
     */
    private void determineResponseCaseD(final List<Rule> list_triggeredRules) {
        final Rule rule = list_triggeredRules.parallelStream()
                .sorted((ruleA, ruleB) -> Long.compare(ruleA.getLastUsedTime(), ruleB.getLastUsedTime()))
                .findFirst()
                .get();

        rule.updateLastUsedTime();
        publisher.publishResponses(this, arrayListMultimap_ruleResponseAssociations.get(rule));
    }

    /**
     * If multiple Rules were found in the set of triggered rules
     * and no other case applies, then a weighted average is done
     * taking into account both the scores and the last used times
     * of each Rule to determine which Rule to use.
     *
     * All Rules with no Criterion will be ignored.
     *
     * @param list_triggeredRules
     *         The Rules of which one or more will be responded to.
     *
     * @param hashMap_scores
     *         todo JavaDoc
     */
    private void determineResponseCaseE(final List<Rule> list_triggeredRules, final ConcurrentHashMap<Rule, Double> hashMap_scores) {
        final ArrayList<Integer> arrayList_incidesToUse = new ArrayList<>();


        double highestScore = 0;
        final long currentTime = System.currentTimeMillis();
        int counter = 0;

        final double lowestCriterionScore = getLowestCriterionScore(list_triggeredRules, hashMap_scores);
        final double highestCriterionScore = getHighestCriterionScore(list_triggeredRules, hashMap_scores);

        final double oldestLastUsedTime = getOldestLastUsedTime(list_triggeredRules);
        final double newestLastUsedTime = getNewestLastUsedTime(list_triggeredRules);

        double normalizedScore;
        double normalizedLUUT;
        double finalScore;

        for(final Rule rule : list_triggeredRules) {
                /*
                 * If there are Criterion associated with the Rule and at-least one of them evaluates
                 * to TRUE, then continue.
                 *
                 * AND
                 *
                 * If there are no Criterion associated with the Rule, then continue.
                 */
            if(hashMap_scores.get(rule) > 0 && getAssociatedCriterions(rule).size() != 0) {
                normalizedScore = normalize(hashMap_scores.get(rule), lowestCriterionScore, highestCriterionScore);
                normalizedLUUT = normalize(rule.getLastUsedTime(), oldestLastUsedTime, newestLastUsedTime) * (currentTime - rule.getLastUsedTime())/1000;

                finalScore = (normalizedScore * 0.6f) + (normalizedLUUT * 0.4f);

                if(finalScore > highestScore) {
                    arrayList_incidesToUse.clear();
                    highestScore = finalScore;
                } else if(finalScore == highestScore) {
                    arrayList_incidesToUse.add(counter);
                }
            }

            counter ++;
        }


        /*
         * If there is only one Rule with the highest score, then use
         * it.
         *
         * If there are multiple Rules that share the highest score,
         * then randomly pick one to use.
         */
        final int indexToUse = random.nextInt(arrayList_incidesToUse.size());

        list_triggeredRules.get(indexToUse).updateLastUsedTime();
        publisher.publishResponses(this, arrayListMultimap_ruleResponseAssociations.get(list_triggeredRules.get(indexToUse)));
    }

    /**
     * Determines the lowest Criterion score from the specified
     * Rule<->Score associations.
     *
     * @param list_triggeredRules
     *         todo JavaDoc
     *
     * @param hashMap_scores
     *         todo JavaDoc
     *
     * @return
     *         The lowest Criterion score from the specified
     *         Rule<->Score associations.
     */
    private double getLowestCriterionScore(final List<Rule> list_triggeredRules, final ConcurrentHashMap<Rule, Double> hashMap_scores) {
        double lowest = Double.MAX_VALUE;
        double currentVal;

        for(final Rule rule : list_triggeredRules) {
            currentVal = hashMap_scores.get(rule);

            if(currentVal < lowest) {
                lowest = currentVal;
            }
        }

        return lowest;
    }

    /**
     * Determines the lowest Criterion score from the specified
     * Rule<->Score associations.
     *
     * @param list_triggeredRules
     *         todo JavaDoc
     *
     * @param hashMap_scores
     *         todo JavaDoc
     *
     * @return
     *         The lowest Criterion score from the specified
     *         Rule<->Score associations.
     */
    private double getHighestCriterionScore(final List<Rule> list_triggeredRules, final ConcurrentHashMap<Rule, Double> hashMap_scores) {
        double highest = Double.MIN_VALUE;
        double currentVal;

        for(final Rule rule : list_triggeredRules) {
            currentVal = hashMap_scores.get(rule);

            if(currentVal > highest) {
                highest = currentVal;
            }
        }

        return highest;
    }

    /**
     * Determines the oldest last used time of any Rule
     * from the specified rules.
     *
     * @param list_triggeredRules
     *         todo JavaDoc
     *
     * @return
     *         The oldest last used time of any Rule
     *         from the specified Rules.
     */
    private long getOldestLastUsedTime(final List<Rule> list_triggeredRules) {
        long oldest = Long.MAX_VALUE;

        for(final Rule rule : list_triggeredRules) {
            if(rule.getLastUsedTime() < oldest) {
                oldest = rule.getLastUsedTime();
            }
        }

        return oldest;
    }

    /**
     * Determines the newest last used time of any Rule
     * from the specified rules.
     *
     * @param set_triggeredRules
     *         todo JavaDoc
     *
     * @return
     *         The newest last used time of any Rule
     *         from the specified Rules.
     */
    private long getNewestLastUsedTime(final List<Rule> set_triggeredRules) {
        long newest = Long.MIN_VALUE;

        for(final Rule rule : set_triggeredRules) {
            if(rule.getLastUsedTime() > newest) {
                newest = rule.getLastUsedTime();
            }
        }

        return newest;
    }

    /**
     * Determines the weight of all Criterion that evaluate to TRUE
     * for the specified Rule.
     *
     * @param rule
     *         The Rule whose Criterion weight is to be evaluated.
     *
     * @return
     *         The combined weight of all TRUE Criterion divided by the
     *         weight of all Criterion combined.
     */
    private double determineCriterionWeight(final Rule rule) {
        double totalWeight = 0;
        double trueWeight = 0;

        for(final Criterion criterion : arrayListMultimap_ruleCriterionAssociations.get(rule)) {
            totalWeight += criterion.getWeight();

            if(criterion.getIsTrue()) {
                trueWeight += criterion.getWeight();
            }
        }

        return trueWeight / totalWeight;
    }

    /**
     * Normalizes the specified value to a percentage between the specified
     * minimum and maximum values.
     *
     * @param value
     *         The value to normalize.
     *
     * @param minimum
     *         The minimum value.
     *
     * @param maximum
     *         The maximum value.
     *
     * @return
     *         The normalized value.
     */
    private double normalize(final double value, final double minimum, final double maximum) {
        double numerator = value - minimum;
        double denominator = maximum - minimum;

        if(denominator == 0) {
            denominator = 1;
        }

        return numerator / denominator;
    }





    /**
     * Attempts to retrieve the Value associated with the specified Key
     * from the splayTree_context of the specified user.
     *
     * @param userId
     *         The ID of the user to retrieve the value from.
     *
     * @param key
     *        The Key associated with the Value to retrieve.
     *
     * @return
     *        Returns the Value as an Object. The Object type returned
     *        is specified through the ValueType field of the Context
     *        and is automatically dealt with before the return.
     *
     *        So, if the key "Total enemies" is used, the Value retrieved
     *        might have a ValueType of Integer. If this is the case, then
     *        the Object returned will be an integer.
     *
     *        If anything goes wrong, String of data itself is returned.
     */
    public Object getValue(final long userId, final String key) {
        final User user = hashMap_users.get(userId);

        // Write Lock User's SplayTree:
        user.getLock_splayTree_context().readLock().lock();

        // Get Data:
        final SplayTree<String, Context> splayTree_context = user.getSplayTree_context();

        final ValueType valueType = splayTree_context.get(key).getValueType();
        final String value = splayTree_context.get(key).getValue();

        // Unlock the Lock:
        user.getLock_splayTree_context().readLock().unlock();


        switch(valueType) {
            case BYTE: {
                return Byte.valueOf(value);
            }
            case SHORT: {
                return Short.valueOf(value);
            }
            case INTEGER: {
                return Integer.valueOf(value);
            }
            case LONG: {
                return Long.valueOf(value);
            }
            case FLOAT: {
                return Float.valueOf(value);
            }
            case DOUBLE: {
                return Double.valueOf(value);
            }
            case BOOLEAN: {
                return Boolean.valueOf(value);
            }
            default: {
                return value;
            }
        }
    }

    /**
     * Attempts to set the Value associated with the specified Key to
     * the specified Value within the splayTree_context.
     *
     * @param userId
     *         The ID of the user whose value is to be set.
     *
     * @param key
     *        The Key associated with the Value to retrieve.
     *
     * @param newValue
     *        The new Value to place into the Context.
     */
    public void setValue(final long userId, final String key, final String newValue) {
        hashMap_users.get(userId)
                .getSplayTree_context()
                .get(key)
                .setValue(newValue);
    }


    /**
     * Adds the specified User into the Dynamic Dialog System.
     * If a User with the same ID as the specified User already
     * exists, then no duplicate is added.
     *
     * @param user
     *         The User to add.
     *
     * @throws
     *         If a User with the same ID as the specified
     *         User already exists.
     */
    public void addUser(final User user) throws IllegalArgumentException {
        if(! hashMap_users.containsKey(user.getId())) {
            hashMap_users.put(user.getId(), user);
        } else {
            throw new IllegalArgumentException("A user with the ID " + user.getId() + " already exists.");
        }
    }

    /**
     * Adds the specified Event into the Dynamic Dialog System.
     * If the Event already exists, then no duplicate is added.
     *
     * @param event
     *         The Event to add.
     */
    public void addEvent(final String event) {
        lock_arrayList_events.writeLock().lock();

        if(! arrayList_events.contains(event)) {
            arrayList_events.add(event);
        }

        lock_arrayList_events.writeLock().unlock();
    }

    /**
     * Adds the specified Context into the Dynamic Dialog System.
     *
     * @param context
     *         The Context to add into the Dynamic Dialog System.
     */
    public void addContext(final Context context) {
        for (Map.Entry<Long, User> user : hashMap_users.entrySet()) {
            user.getValue().getLock_splayTree_context().writeLock().lock();

            user.getValue()
                    .getSplayTree_context()
                    .put(context.getName(), context);

            user.getValue().getLock_splayTree_context().writeLock().unlock();
        }


        lock_arrayList_contextNames.writeLock().lock();

        arrayList_contextNames.add(context.getName());

        lock_arrayList_contextNames.writeLock().unlock();
    }

    /**
     * Removes the specified User from the Dynamic Dialog System.
     *
     * If no User matching the exact ID and User object of the
     * specified User is found, then nothing happens.
     *
     * @param user
     *         The User to remove.
     */
    public void removeUser(final User user) {
        hashMap_users.remove(user.getId(), user);
    }

    /**
     * Removes the specified Context from the Dynamic Dialog System.
     *
     * @param context
     *         The Context to remove from the Dynamic Dialog System.
     */
    public void removeContext(final Context context) throws UnsupportedOperationException {
        // If the Context to be removed is still in-use by some Criterion in the System,
        // then throw an exception to prevent it from being removed.
        lock_arrayList_criterion.readLock().lock();

        arrayList_criterion.parallelStream()
                .filter(criterion -> criterion != null)
                .filter(criterion -> criterion.getContext().equals(context))
                .forEach(criterion -> {
                    throw new UnsupportedOperationException("The following Context is still in-use by the following Criterion" +
                            ", it cannot be removed from the Dynamic Dialog System.\n" +
                            context.toString() + "\n\n" + criterion.toString());
                });

        lock_arrayList_criterion.readLock().unlock();


        // Remove the Context from all users:
        for (Map.Entry<Long, User> user : hashMap_users.entrySet()) {
            user.getValue().getLock_splayTree_context().writeLock().lock();

            user.getValue()
                    .getSplayTree_context()
                    .remove(context.getName());

            user.getValue().getLock_splayTree_context().writeLock().unlock();

        }


        // Remove the Context's last used time from the DDS:
        hashMap_context_lastUsedTime.remove(context);


        // Remove the Context's name from the DDS:
        lock_arrayList_contextNames.writeLock().lock();

        arrayList_contextNames.remove(context.getName());

        lock_arrayList_contextNames.writeLock().unlock();
    }

    /**
     * Adds the specified Criterion into the Dynamic Dialog System.
     *
     * @param criterion
     *         The Criterion to add into the Dynamic Dialog System.
     */
    public void addCriterion(final Criterion criterion) {
        lock_arrayList_criterion.writeLock().lock();

        arrayList_criterion.add(criterion);

        lock_arrayList_criterion.writeLock().unlock();
    }

    /**
     * Removes the specified Criterion from the Dynamic Dialog System.
     *
     * @param criterion
     *         The Criterion to remove from the Dynamic Dialog System.
     */
    public void removeCriterion(final Criterion criterion) throws UnsupportedOperationException {
        // If the Criterion to be removed is still in-use by some Rule in the System,
        // then throw an exception to prevent it from being removed.
        lock_arrayList_rules.readLock().lock();

        arrayList_rules.parallelStream()
                .filter(rule -> rule != null)
                .filter(rule -> arrayListMultimap_ruleCriterionAssociations.get(rule).contains(criterion))
                .forEach(rule -> {
                    throw new UnsupportedOperationException("The following Criterion is still in-use by the following Rule" +
                            ", it cannot be removed from the Dynamic Dialog System.\n" +
                            criterion.toString() + "\n\n" + rule.toString());
                });

        lock_arrayList_rules.readLock().unlock();


        // Remove the Rule's last used time from the DDS:
        hashMap_criterion_lastUsedTime.remove(criterion);


        // Remove the Rule's name from the DDS:
        lock_arrayList_criterion.writeLock().lock();

        arrayList_criterion.remove(criterion);

        lock_arrayList_criterion.writeLock().unlock();
    }

    /**
     * Adds the specified Response into the Dynamic Dialog System.
     *
     * @param response
     *         The Response to add into the Dynamic Dialog System.
     */
    public void addResponse(final Response response) {
        lock_arrayList_responses.writeLock().lock();

        arrayList_response.add(response);

        lock_arrayList_responses.writeLock().unlock();
    }

    /**
     * Removes the specified Response from the Dynamic Dialog System.
     *
     * @param response
     *         The Response to remove from the Dynamic Dialog System.
     */
    public void removeResponse(final Response response) throws UnsupportedOperationException {
        // If the Response to be removed is still in-use by some Rule in the System,
        // then throw an exception to prevent it from being removed.
        lock_arrayList_rules.readLock().lock();

        arrayList_rules.parallelStream()
                .filter(rule -> rule != null)
                .filter(rule -> arrayListMultimap_ruleResponseAssociations.get(rule).contains(response))
                .forEach(rule -> {
                    throw new UnsupportedOperationException("The following Response is still in-use by the following Rule" +
                            ", it cannot be removed from the Dynamic Dialog System.\n" +
                            response.toString() + "\n\n" + rule.toString());
                });

        lock_arrayList_rules.readLock().unlock();


        // Remove the Response's last used time from the DDS:
        hashMap_response_lastUsedTime.remove(response);


        // Remove the Response's name from the DDS:
        lock_arrayList_responses.writeLock().lock();

        arrayList_response.remove(response);

        lock_arrayList_responses.writeLock().unlock();
    }

    /**
     * Adds the specified Rule into the Dynamic Dialog System.
     *
     * @param rule
     *         The Rule to add into the Dynamic Dialog System.
     */
    public void addRule(final Rule rule) {
        lock_arrayList_rules.readLock().lock();

        arrayList_rules.add(rule);

        lock_arrayList_rules.readLock().unlock();
    }

    /**
     * Removes the specified Rule from the Dynamic Dialog System.
     *
     * @param rule
     *         The Rule to remove from the Dynamic Dialog System.
     */
    public void removeRule(final Rule rule) {
        removeRuleCriterionAssociations(rule);
        removeRuleEventAssociations(rule);
        removeRuleResponseAssociations(rule);


        // Remove the Rule's last used time from the DDS:
        hashMap_rules_lastUsedTime.remove(rule);


        // Remove the Rule from the DDS:
        lock_arrayList_rules.writeLock().lock();

        arrayList_rules.remove(rule);

        lock_arrayList_rules.writeLock().unlock();
    }

    /**
     * Adds the specified Rule<->Criterion Association to the Dynamic Dialog system.
     *
     * @param rule
     *         The rule to use in the association.
     *
     * @param criterion
     *         The criterion to use in the association.
     */
    public void addRuleCriterionAssociation(final Rule rule, final Criterion criterion) {
        lock_arrayListMultimap_ruleCriterionAssociations.writeLock().lock();

        arrayListMultimap_ruleCriterionAssociations.put(rule, criterion);

        lock_arrayListMultimap_ruleCriterionAssociations.writeLock().unlock();
    }

    /**
     * Removes all Rule<->Criterion Associations, that use the specified rule,
     * from the Dynamic Dialog System.
     *
     * @param rule
     *         The Rule whose associations are to be removed.
     */
    private void removeRuleCriterionAssociations(final Rule rule) {
        lock_arrayListMultimap_ruleCriterionAssociations.writeLock().lock();

        arrayListMultimap_ruleCriterionAssociations.removeAll(rule);

        lock_arrayListMultimap_ruleCriterionAssociations.writeLock().unlock();
    }

    /**
     * Adds the specified Event<->Rule Association to the Dynamic Dialog system.
     *
     * @param event
     *         The event to use in the association.
     *
     * @param rule
     *         The rule to use in the association.
     */
    public void addRuleEventAssociation(final String event, final Rule rule) {
        lock_arrayListMultimap_ruleEventAssociations.writeLock().lock();

        arrayListMultimap_ruleEventAssociations.put(event, rule);

        lock_arrayListMultimap_ruleEventAssociations.writeLock().unlock();
    }

    /**
     * Removes all Event<->Rule Associations, that use the specified rule,
     * from the Dynamic Dialog System.
     *
     * @param rule
     *         The Rule whose associations are to be removed.
     */
    private void removeRuleEventAssociations(final Rule rule) {
        lock_arrayListMultimap_ruleEventAssociations.writeLock().lock();

        arrayListMultimap_ruleEventAssociations.entries()
                .removeIf(entry -> entry.getValue().equals(rule));

        lock_arrayListMultimap_ruleEventAssociations.writeLock().unlock();
    }

    /**
     * Adds the specified Rule<->Response Association to the Dynamic Dialog system.
     *
     * @param rule
     *         The rule to use in the association.
     *
     * @param response
     *         The response to use in the association.
     */
    public void addRuleResponseAssociation(final Rule rule, final Response response) {
        lock_arrayListMultimap_ruleResponseAssociations.writeLock().lock();

        arrayListMultimap_ruleResponseAssociations.put(rule, response);

        lock_arrayListMultimap_ruleResponseAssociations.writeLock().unlock();
    }

    /**
     * Removes all Rule<->Response Associations, that use the specified rule,
     * from the Dynamic Dialog System.
     *
     * @param rule
     *         The Rule whose associations are to be removed.
     */
    private void removeRuleResponseAssociations(final Rule rule) {
        lock_arrayListMultimap_ruleResponseAssociations.writeLock().lock();

        arrayListMultimap_ruleResponseAssociations.removeAll(rule);

        lock_arrayListMultimap_ruleResponseAssociations.writeLock().unlock();
    }





    /** @return The Publisher to use when handling Responses. */
    public Publisher getPublisher() {
        return publisher;
    }

    /** @return A copy of the ArrayList containing all Rules, with their IDs as Keys. */
    public List<Rule> getRules() {
        lock_arrayList_rules.readLock().lock();

        final List<Rule> list = new ArrayList<>(arrayList_rules);

        lock_arrayList_rules.readLock().unlock();

        return list;
    }

    /** @return A copy of the ArrayList containing all Events that are used by the Dynamic Dialog System. */
    public List<String> getEvents() {
        lock_arrayList_events.readLock().lock();

        final List<String> list = new ArrayList<>(arrayList_events);

        lock_arrayList_events.readLock().unlock();

        return list;
    }

    /** @return A copy of the ArrayList containing all ResponseTypes that are used by the Dynamic Dialog System. */
    public List<String> getResponseTypes() {
        lock_arrayList_responseTypes.readLock().lock();

        final List<String> list = new ArrayList<>(arrayList_responseTypes);

        lock_arrayList_responseTypes.readLock().unlock();

        return list;
    }

    /** @return A copy of the ArrayList containing all Context names that are used by the Dynamic Dialog System.. */
    public List<String> getContextNames() {
        lock_arrayList_contextNames.readLock().lock();

        final List<String> list = new ArrayList<>(arrayList_contextNames);

        lock_arrayList_contextNames.readLock().unlock();

        return list;
    }





    /**
     * Locates all Criterions associated with the specified Rule.
     *
     * @param rule
     *         The Rule to search with.
     *
     * @return
     *         A list containing all Criterions associated with the specified Rule.
     */
    public List<Criterion> getAssociatedCriterions(final Rule rule) {
        lock_arrayListMultimap_ruleCriterionAssociations.readLock().lock();

        final List<Criterion> list = arrayListMultimap_ruleCriterionAssociations.get(rule);

        lock_arrayListMultimap_ruleCriterionAssociations.readLock().unlock();

        return list;
    }

    /**
     * Locates all Events associated with the specified Rule.
     *
     * @param rule
     *         The Rule to search with.
     *
     * @return
     *         A list containing all Events associated with the specified Rule.
     */
    public List<String> getAssociatedEvents(final Rule rule) {
        final List<String> list_associatedEvents = new ArrayList<>();

        lock_arrayListMultimap_ruleEventAssociations.readLock().lock();

        arrayListMultimap_ruleEventAssociations.entries()
                .parallelStream()
                .filter(entry -> entry.getValue().equals(rule))
                .forEach(entry -> list_associatedEvents.add(entry.getKey()));

        lock_arrayListMultimap_ruleEventAssociations.readLock().unlock();

        return list_associatedEvents;
    }

    /**
     * Locates all Responses associated with the specified Rule.
     *
     * @param rule
     *         The Rule to search with.
     *
     * @return
     *         A list containing all Responses associated with the specified Rule.
     */
    public List<Response> getAssociatedResponses(final Rule rule) {
        lock_arrayListMultimap_ruleResponseAssociations.readLock().lock();

        final List<Response> list = arrayListMultimap_ruleResponseAssociations.get(rule);

        lock_arrayListMultimap_ruleResponseAssociations.readLock().unlock();

        return list;
    }

    /**
     * Locates all Rules associated with the specified Event.
     *
     * @param event
     *         The Event to search with.
     *
     * @return
     *         A list containing all Rules associated with the specified Event.
     */
    public List<Rule> getAssociatedRules(final String event) {
        lock_arrayListMultimap_ruleEventAssociations.readLock().lock();

        final List<Rule> list = arrayListMultimap_ruleEventAssociations.get(event);

        lock_arrayListMultimap_ruleEventAssociations.readLock().unlock();

        return list;
    }





    /**
     * Set a new set of Events that can be used by the Dynamic Dialog System.
     *
     * @param arrayList_events
     *         The Events that can be used by the Dynamic Dialog system.
     */
    public void setEvents(final ArrayList<String> arrayList_events) {
        lock_arrayList_events.writeLock().lock();

        this.arrayList_events = arrayList_events;

        lock_arrayList_events.writeLock().unlock();
    }

    /**
     * Set a new set of ResponseTypes that can be used by the Dynamic Dialog System.
     *
     * @param arrayList_responseTypes
     *         The ResponseTypes that can be used by the Dynamic Dialog system.
     */
    public void setResponseTypes(final ArrayList<String> arrayList_responseTypes) {
        lock_arrayList_responseTypes.writeLock().lock();

        this.arrayList_responseTypes = arrayList_responseTypes;

        lock_arrayList_responseTypes.writeLock().lock();
    }
}