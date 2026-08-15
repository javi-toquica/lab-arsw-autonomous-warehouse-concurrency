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
        // Count this robot as parked, and announce it, exactly once per pause
        // episode. Re-incrementing/re-notifying on every loop iteration (as a
        // previous version of this method did) causes every spurious wakeup
        // to trigger a fresh notifyAll(), which can cascade into a storm of
        // robots repeatedly waking each other and starve the coordinator
        // thread waiting in awaitAllPaused() under the JVM's non-fair
        // synchronized locks.
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