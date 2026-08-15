package edu.eci.arsw.warehouse.core;

/**
 * Monitor-based pause/resume control.
 * Uses synchronized + wait()/notifyAll() instead of busy waiting.
 */
public class SimulationControl {

    private boolean paused;
    private int activeRobots;   // robots que aún no han terminado su trabajo
    private int parkedRobots;   // robots actualmente detenidos en wait()

    public synchronized void registerRobot() {
        activeRobots++;
    }

    public synchronized void unregisterRobot() {
        activeRobots--;
        notifyAll();
    }

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
        notifyAll();
    }

    public synchronized void awaitIfPaused() throws InterruptedException {
        if (!paused) {
            return;
        }
        // Count this robot as parked, and notify, exactly once per pause
        // episode. An earlier version repeated parkedRobots++ / notifyAll()
        // on every loop iteration, so any wakeup (even one that was not
        // caused by resume()) made this robot call notifyAll() again, which
        // woke the other parked robots too, which each did the same thing.
        // That chain of robots re-notifying each other could run for a long
        // time before the coordinator thread in awaitAllPaused() got a
        // chance to acquire the lock and check whether everyone was already
        // paused, which is what caused PauseResumeDemo to hang.
        parkedRobots++;
        notifyAll();
        try {
            while (paused) {
                wait();
            }
        } finally {
            parkedRobots--;
        }
    }

    public synchronized void awaitAllPaused() throws InterruptedException {
        while (paused && parkedRobots < activeRobots) {
            wait();
        }
    }

    public synchronized boolean isPaused() {
        return paused;
    }
}