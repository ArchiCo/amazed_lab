package amazed.solver;

import amazed.maze.Maze;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;

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
    protected static AtomicBoolean goalIdentified = new AtomicBoolean(false);
    
    // Visited nodes will be shared statically between threads, 
    // hence we'll be using thread-safe implementations of SkipListSet
    protected static ConcurrentSkipListSet<Integer> visited = new ConcurrentSkipListSet<>();
    protected ConcurrentHashMap<Integer, Integer> predecessor = new ConcurrentHashMap<>();
    
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
    }
    
    // Creating a new branch out of the root
    public ForkJoinSolver (ForkJoinSolver root, int start, int forkAfter) {  
        this(root.maze);
        this.predecessor = root.predecessor;
        this.forkAfter = root.forkAfter;
        this.start = start;   
    }
    

    /**
     * Searches for and returns the path, as a list of node
     * identifiers, that goes from the start node to a goal node in
     * the maze. If such a path cannot be found (because there are no
     * goals, or all goals are unreachable), the method returns
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
        if (!visited.contains(start) && goalIdentified.get() == false) {             // If start wasn't visited
            int player  = maze.newPlayer(start),    // Initialize player avatar
                current; 
            frontier.push(start);                   // Push start node to stack
            while (frontier.empty() == false && 
                   goalIdentified.get() == false) { // As long as not all nodes have been
                                                    // processed
                current = frontier.pop();           // Pop the first node in the stack
                                                    // to process (LIFO)
               // Check if the current node is a goal, and if true
                if (maze.hasGoal(current) == true) {
                        goalIdentified.set(true); // designate and return goal path to origin node
                        maze.move(player, current);             
                        return pathFromTo(start, current);
                }
                
               // Otherwise, check if the node was already visited
                if (visited.add(current) == true) {    // If it wasn't visited, then
                    maze.move(player, current);        // Move player avatar
                        for (int nb : maze.neighbors(current)) {
                            frontier.push(nb);         // Add neighbor to frontier stack
                            if (!visited.contains(nb))
                                 predecessor.putIfAbsent(nb, current); // Atomically add a predecessor, unless it was already declared  
                        }
                        
                    // Check neighboring nodes and check if forking is necessary,
                    // as well as return the final result (null or path to origin)
                    if ((maze.neighbors(current).size()  > 2 ||
                         (maze.neighbors(current).size() == 2 && 
                         visited.size() == 1 ))) {
                        List<ForkJoinSolver> forkedTasks = spawnForks();
                        for (ForkJoinSolver task : forkedTasks) {
                            List<Integer> result = task.join();
                            if (!result.isEmpty() && result != null) { // if result is null and/or global path was found
                                List<Integer> currentSection = pathFromTo(start, current);
                                currentSection.addAll(result);
                                return currentSection; 
                            }
                        }
                    }
                }
            }
        }
        return new LinkedList<>();
    }

    // Spawn forks based on number of unvisited frontier neighbor nodes
    private List<ForkJoinSolver> spawnForks() {
        List<ForkJoinSolver> spread = new LinkedList<>();
        for (int i = 0; i < this.frontier.size(); i++) {
            if (!visited.contains(frontier.get(i)) && frontier.get(i) != null ) {
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
    protected List<Integer> pathFromTo(int from, int to) {
        List<Integer> path = new LinkedList<>();
        Integer current = to;
        while (current != from) {
            path.add(current);
            current = predecessor.get(current);
            if (current == null) {
                System.out.println("Error in From (" + from + ") : To (" + to + ")");
                return new LinkedList<>();
            }
        }
        path.add(from);
        Collections.reverse(path);
        return path;
    }
}
