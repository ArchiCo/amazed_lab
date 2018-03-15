package amazed.solver;

import amazed.maze.Maze;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <code>ForkJoinSolver</code> implements a solver for
 * <code>Maze</code> objects using a fork/join multi-thread
 * depth-first search.
 * <p>
 * Instances of <code>ForkJoinSolver</code> should be run by a
 * <code>ForkJoinPool</code> object.
 */


public class ForkJoinSolver
    extends SequentialSolver
{
    // Atomic Boolean implementation for a shared goal finding state variable
    protected AtomicBoolean goalIdentified = new AtomicBoolean(false);
    
    // Visited nodes will be shared statically between threads, 
    // hence we'll be using thread-safe implementations of HashMap
    protected static ConcurrentSkipListSet<Integer> visited = new ConcurrentSkipListSet<>(); // 
    
    // Mutex/Binary semaphore for handling shared resource access
    protected static Semaphore mutex = new Semaphore(1, true);
    protected HashMap<Integer, Integer> predecessor = new HashMap<Integer, Integer>();
    
    /* Origin/starting node used for streamlining final pathfinding between nodes
       Instead of rebuilding the back from forked child threads to parents, we simply
       return the final pathFromTo state by the child that identified and moved to the
       goal node while flagging other threads via atomic boolean for exit/returning null */
    protected static int origin = 0;
    /**
     * Creates a solver that searches in <code>maze</code> from the
     * start node to a goal.
     *
     * @param maze   the maze to be searched
     */
    public ForkJoinSolver(Maze maze)
    {
        super(maze);
    }

    /**
     * Creates a solver that searches in <code>maze</code> from the
     * start node to a goal, forking after a given number of visited
     * nodes.
     *
     * @param maze        the maze to be searched
     * @param forkAfter   the number of steps (visited nodes) after
     *                    which a parallel task is forked; if
     *                    <code>forkAfter &lt;= 0</code> the solver never
     *                    forks new tasks
     */
    public ForkJoinSolver(Maze maze, int forkAfter)
    {
        this(maze);
        this.forkAfter = forkAfter;
        origin = maze.start();

    }
    
    // Creating a new branch out of the root
    public ForkJoinSolver (ForkJoinSolver root, int start, int forkAfter) {  
        this(root.maze);
        this.predecessor = root.predecessor;
        this.forkAfter = root.forkAfter;
        this.start = start;
        this.goalIdentified = root.goalIdentified;       
    }
    

    /**
     * Searches for and returns the path, as a list of node
     * identifiers, that goes from the start node to a goal node in
     * the maze. If such a path cannot be found (because there are no
     * goals, or all goals are unreacheable), the method returns
     * <code>null</code>.
     *
     * @return   the list of node identifiers from the start node to a
     *           goal node in the maze; <code>null</code> if such a path cannot
     *           be found.
     */
    @Override
    public List<Integer> compute()
    {
        return parallelSearch();
    }

    private List<Integer> parallelSearch()
    {
        if (!visited.contains(start)) {         // If start wasn't visited
            int player = maze.newPlayer(start); // Initialize player avatar
            frontier.push(start);               // Push start node to stack
            while (!frontier.empty()) {         // As long as not all nodes have been
                                                // processed
                int current = frontier.pop();   // Pop the first node in the stack
                                                // to process (LIFO)
               // Check if the current node is a goal, and if true
                if (goalIdentified.get() == false 
                        && maze.hasGoal(current)) {
                    if (mutex.tryAcquire()) {     // Halt thread propagation,
                        goalIdentified.set(true); // designate and return goal path to origin node
                        maze.move(player, current);
                        mutex.release();
                        return pathFromTo(origin, current);
                    }
                }
                
               // Otherwise, check if the node was already visited
                if (goalIdentified.get() == false 
                        && !visited.contains(current)) { // If it wasn't visited, then
                    try {
                        // Lock in shared resource
                        mutex.acquire();
                        boolean provisioned = false;
                        try {
                            if (!visited.contains(current) && goalIdentified.get() == false) {
                                visited.add(current); // Designate visited node
                                provisioned = true;
                            }
                        } finally {
                            mutex.release();
                            if (provisioned == true && goalIdentified.get() == false) {
                                maze.move(player, current); // Move player avatar
                                for (int nb : maze.neighbors(current)) {
                                    frontier.push(nb);         // Add neighbor to frontier stack
                                    if (!visited.contains(nb)) // and designate predecessor
                                        predecessor.put(nb, current);
                                }
                            }
                        }
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }

                    // Check neighboring nodes and check if forking is necessary,
                    // as well as return the final result (null or path to origin)
                    if ((maze.neighbors(current).size() > 2
                            || maze.neighbors(current).size() == 2 && visited.size() == 1)
                            && goalIdentified.get() == false) {
                        List<ForkJoinSolver> forkedTasks = spawnForks();
                        for (ForkJoinSolver task : forkedTasks) {
                            List<Integer> result = task.join();
                            if (result != null) { // if result is null and/or
                                                  // global path was found
                                return result;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    // Spawn forks based on number of unvisited frontier neighbor nodes
    private List<ForkJoinSolver> spawnForks() {
        List<ForkJoinSolver> spread = new ArrayList<>();
        for (int i = 0; i < this.frontier.size(); i++) {
            if (!visited.contains(frontier.get(i))) {
                spread.add(new ForkJoinSolver(this, frontier.get(i), forkAfter));
                spread.get(spread.size() - 1).fork();
            }
        }
        return spread;
    }

    /**
     * Returns the connected path, as a list of node identifiers, that
     * goes from node <code>from</code> to node <code>to</code>
     * following the inverse of relation <code>predecessor</code>. If
     * such a path cannot be reconstructed from
     * <code>predecessor</code>, the method returns <code>null</code>.
     *
     * @param from   the identifier of the initial node on the path
     * @param to     the identifier of the final node on the path
     * @return       the list of node identifiers from <code>from</code> to
     *               <code>to</code> if such a path can be reconstructed from
     *               <code>predecessor</code>; <code>null</code> otherwise
     */
    @Override
    protected List<Integer> pathFromTo(int from, int to) {
        List<Integer> path = new LinkedList<>();
        Integer current = to;
        while (current != from) {
            path.add(current);
            current = predecessor.get(current);
            if (current == null)
                return null;
        }
        path.add(from);
        Collections.reverse(path);
        return path;
    }
}
