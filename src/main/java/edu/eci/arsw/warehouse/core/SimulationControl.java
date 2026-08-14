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
        while (paused) {
            parkedRobots++;
            notifyAll();
            try {
                wait();
            } finally {
                parkedRobots--;
            }
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