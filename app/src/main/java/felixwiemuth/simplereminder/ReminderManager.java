/*
 * Copyright (C) 2018 Felix Wiemuth
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package felixwiemuth.simplereminder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import felixwiemuth.simplereminder.data.Reminder;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import static felixwiemuth.simplereminder.SharedPrefs.PREF_STATE_CURRENT_REMINDERS;
import static felixwiemuth.simplereminder.SharedPrefs.PREF_STATE_NEXTID;

/**
 * Manages current reminders by allowing to add and change reminders, scheduling notifications. Due reminders are handled by {@link ReminderService}.
 *
 * @author Felix Wiemuth
 */
public class ReminderManager {

    public static class ReminderNotFoundException extends RuntimeException {
        public ReminderNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Lock guarding the state preferences ({@link SharedPrefs#PREFS_STATE}). This reference being null is equivalent to the lock not being aquired.
     */
    private static ReentrantLock prefStateLock;

    private static void lock() {
        if (prefStateLock == null) {
            prefStateLock = new ReentrantLock();
        }
        prefStateLock.lock();
    }

    private static void unlock() {
        if (prefStateLock != null) {
            prefStateLock.unlock();
        }
    }

    interface StatePrefEditOperation {
        void edit(SharedPreferences prefs, SharedPreferences.Editor editor);
    }

    interface RemindersEditOperation {
        List<Reminder> update(List<Reminder> reminders);
    }

    /**
     * Edit the state preferences ({@link SharedPrefs#PREFS_STATE}) exclusively and commit after the operation has successfully completed. This ensures that different threads editing these preferences do not overwrite their changes.
     *
     * @param context
     * @param operation
     */
    @SuppressLint("ApplySharedPref")
    private static void performExclusivelyOnStatePrefsAndCommit(Context context, StatePrefEditOperation operation) {
        lock();
        try {
            SharedPreferences prefs = SharedPrefs.getStatePrefs(context);
            SharedPreferences.Editor editor = prefs.edit();
            operation.edit(prefs, editor);
            editor.commit();
        } finally {
            unlock();
        }
    }

    private static void updateRemindersList(Context context, RemindersEditOperation operation) {
        performExclusivelyOnStatePrefsAndCommit(context, ((prefs, editor) -> {
            updateRemindersListInEditor(editor, operation.update(getReminders(context)));
        }));
    }

    private static void updateRemindersListInEditor(SharedPreferences.Editor editor, List<Reminder> reminders) {
        editor.putString(PREF_STATE_CURRENT_REMINDERS, Reminder.toJson(reminders));
    }

    /**
     * Add the reminder described by the given builder. A new ID is assigned by this method.
     *
     * @param context
     * @param reminderBuilder
     */
    public static void addReminder(Context context, Reminder.ReminderBuilder reminderBuilder) {
        performExclusivelyOnStatePrefsAndCommit(context,
                (prefs, editor) -> {
                    // Get next reminder ID
                    final int nextId = prefs.getInt(PREF_STATE_NEXTID, 0);
                    reminderBuilder.id(nextId);
                    Reminder reminder = reminderBuilder.build();

                    editor.putInt(PREF_STATE_NEXTID, nextId + 1);
                    addReminderToReminders(prefs, editor, reminder);

                    ReminderService.scheduleReminder(context, reminder);
                });
    }

    /**
     * Add the given reminder (with the given ID).
     *
     * @param context
     * @param reminder
     */
    private static void addReminder(Context context, Reminder reminder) {
        performExclusivelyOnStatePrefsAndCommit(context,
                (prefs, editor) -> {
                    addReminderToReminders(prefs, editor, reminder);
                    ReminderService.scheduleReminder(context, reminder);
                });
    }

    /**
     * Add the given reminder to the list of reminders. No reminder with the same ID must exist yet.
     *
     * @param prefs
     * @param editor
     * @param reminder
     */
    private static void addReminderToReminders(SharedPreferences prefs, SharedPreferences.Editor editor, Reminder reminder) {
        List<Reminder> reminders = getRemindersFromPrefs(prefs);
        for (Reminder r : reminders) {
            if (r.getId() == reminder.getId()) {
                throw new RuntimeException("Cannot add reminder: reminder with id " + reminder.getId() + " already exists.");
            }
        }
        reminders.add(reminder);
        updateRemindersListInEditor(editor, reminders);
    }

    /**
     * Removes the first occurrence of a reminder with the ID of the given reminder on the iterator.
     *
     * @param it
     * @param reminder
     */
    private static void removeReminderWithSameId(Iterator<Reminder> it, Reminder reminder) {
        while (it.hasNext()) {
            Reminder r = it.next();
            if (r.getId() == reminder.getId()) {
                it.remove();
                break;
            }
        }
    }


    /**
     * Removes all occurrence of reminders with the IDs of the given reminders on the iterator.
     *
     * @param it
     * @param reminders
     */
    private static void removeRemindersWithSameId(Iterator<Reminder> it, Iterable<Reminder> reminders) {
        Set<Integer> idsToRemove = new HashSet<>();
        for (Reminder r : reminders) {
            idsToRemove.add(r.getId());
        }
        while (it.hasNext()) {
            Reminder r = it.next();
            if (idsToRemove.contains(r.getId())) {
                it.remove();
            }
        }
    }


    /**
     * Removes all occurrence of reminders with the given IDs.
     *
     * @param it
     * @param ids
     */
    private static void removeRemindersById(Iterator<Reminder> it, Set<Integer> ids) {
        while (it.hasNext()) {
            Reminder r = it.next();
            if (ids.contains(r.getId())) {
                it.remove();
            }
        }
    }

    /**
     * Replaces the reminder with the ID of the given reminder with the given one.
     *
     * @param context
     * @param reminder
     * @param reschedule if true, checks whether the reminder should be rescheduled: If the given reminder's status is not {@link Reminder.Status#SCHEDULED} or its time is not in the future, a possible scheduled notification is cancelled. If the status is {@link Reminder.Status#SCHEDULED} and its time is in the future, a notification is scheduled.
     */
    public static void updateReminder(Context context, Reminder reminder, boolean reschedule) {
        updateRemindersList(context, (currentReminders -> {
            removeReminderWithSameId(currentReminders.iterator(), reminder);
            currentReminders.add(reminder);

            if (reschedule) {
                rescheduleReminder(context, reminder);
            }

            return currentReminders;
        }));
    }

    /**
     * For each given reminder, replaces the existing reminder with the ID of the given reminder with the given one.
     *
     * @param context
     * @param reminders
     * @param reschedule if true, checks whether the reminder should be rescheduled: If the given reminder's status is not {@link Reminder.Status#SCHEDULED} or its time is not in the future, a possible scheduled notification is cancelled. If the status is {@link Reminder.Status#SCHEDULED} and its time is in the future, a notification is scheduled.
     */
    public static void updateReminders(Context context, Iterable<Reminder> reminders, boolean reschedule) {
        updateRemindersList(context, (currentReminders -> {
            removeRemindersWithSameId(currentReminders.iterator(), reminders);
            for (Reminder reminder : reminders) {
                currentReminders.add(reminder);
                if (reschedule) {
                    rescheduleReminder(context, reminder);
                }
            }

            return currentReminders;
        }));
    }

    @FunctionalInterface
    interface ReminderTransformation {
        void run(Reminder reminder);
    }

    /**
     * Update the reminders with the given IDs with the given transformation.
     *
     * @param context
     * @param transformation
     * @param ids
     */
    public static void updateReminders(Context context, ReminderTransformation transformation, Set<Integer> ids, boolean reschedule) {
        updateRemindersList(context, (currentReminders -> {
            for (Reminder reminder : currentReminders) {
                if (ids.contains(reminder.getId())) {
                    transformation.run(reminder);
                    if (reschedule) {
                        rescheduleReminder(context, reminder);
                    }
                }
            }
            return currentReminders;
        }));
    }

    private static void rescheduleReminder(Context context, Reminder reminder) {
        boolean isFuture = reminder.getDate().getTime() > System.currentTimeMillis();
        if (reminder.getStatus() != Reminder.Status.SCHEDULED || !isFuture) {
            ReminderService.cancelReminder(context, reminder.getId());
        }
        if (reminder.getStatus() == Reminder.Status.SCHEDULED && isFuture) {
            ReminderService.scheduleReminder(context, reminder);
        }
    }

//    public static void removeReminder(Context context, Reminder reminder) {
//        updateRemindersList(context, (reminders -> {
//            removeReminderWithSameId(reminders.iterator(), reminder);
//            ReminderService.cancelReminder(context, reminder.getId());
//            return reminders;
//        }));
//    }
//
//    /**
//     * Remove reminders from the current reminders. Cancels pending notifications.
//     *
//     * @param context
//     * @param reminders
//     */
//    public static void removeReminders(Context context, Iterable<Reminder> reminders) {
//        updateRemindersList(context, (currentReminders -> {
//            removeRemindersWithSameId(currentReminders.iterator(), reminders);
//            for (Reminder r : reminders) {
//                ReminderService.cancelReminder(context, r.getId());
//            }
//            return currentReminders;
//        }));
//    }


    /**
     * Remove the reminders with the given IDs from the current reminders. Cancels pending notifications.
     *
     * @param context
     * @param ids
     */
    public static void removeReminders(Context context, Set<Integer> ids) {
        updateRemindersList(context, (currentReminders -> {
            removeRemindersById(currentReminders.iterator(), ids);
            for (Integer id : ids) {
                ReminderService.cancelReminder(context, id);
            }
            return currentReminders;
        }));
    }

    public static List<Reminder> getReminders(Context context) {
        return getRemindersFromPrefs(SharedPrefs.getStatePrefs(context));
    }

    /**
     * Get the reminder with the specified ID.
     *
     * @param context
     * @param id
     * @return
     * @throws ReminderNotFoundException if no reminder with the given ID exists
     */
    public static Reminder getReminder(Context context, int id) throws ReminderNotFoundException {
        List<Reminder> reminders = getReminders(context);
        for (Reminder reminder : reminders) {
            if (reminder.getId() == id) {
                return reminder;
            }
        }
        throw new ReminderNotFoundException("Reminder with id " + id + " does not exist.");
    }

    /**
     * Returns an immutable list of the saved reminders.
     *
     * @param prefs
     * @return
     */
    public static List<Reminder> getRemindersFromPrefs(SharedPreferences prefs) {
        return Reminder.fromJson(prefs.getString(PREF_STATE_CURRENT_REMINDERS, "[]"));
    }
}
