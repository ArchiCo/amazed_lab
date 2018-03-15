package amazed.solver;

import amazed.maze.Maze;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
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
public class ForkJoinSolver extends SequentialSolver {
    
    /**
     * 
     */
    private static final long serialVersionUID = 12L;
    
    /**
     * Creates a solver that searches in <code>maze</code> from the
     * start node to a goal.
     *
     * @param maze   the maze to be searched
     */
    public ForkJoinSolver(Maze maze) {
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
    public ForkJoinSolver (Maze maze, int forkAfter) {
        this(maze);
        this.predecessor = new ConcurrentHashMap<>();
        this.visited = new ConcurrentSkipListSet<>();
        frontier = new Stack<>();
        this.forkAfter = forkAfter;
        this.goalIdentified = new AtomicBoolean(false); 
    }
    
    // Creating a new branch out of the root
    public ForkJoinSolver (ForkJoinSolver root, int start) {  
        this(root.maze);
        this.predecessor = root.predecessor;
        this.visited = root.visited;
        this.frontier = new Stack<>();
        this.forkAfter = root.forkAfter;
        this.start = start;
        this.goalIdentified = root.goalIdentified;       
    }
      
    // Predecessor and visited will be shared statically between
    // threads, hence we'll be using thread-safe implementations of 
    // HashMap and ListSet.
    protected static ConcurrentHashMap<Integer, Integer> predecessor;
    protected static ConcurrentSkipListSet<Integer> visited;
    protected AtomicBoolean goalIdentified;
    
    @Override
    public List<Integer> compute() {  
        return parallelSearch();
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
    
    private List<Integer> parallelSearch() {  
        int player = maze.newPlayer(start);          // Initialize player avatar
        int steps = this.forkAfter;                  // Initialize Number of steps before forking
        frontier.push(start);                        // Push start node to stack
        while(!frontier.isEmpty()) {                 // As long as not all nodes have been processed
            int current = frontier.pop();            // Pop the first node in the stack to process (LIFO)
            if(maze.hasGoal(current)) {              // Check if the current node is a goal, and if true
                goalIdentified.set(true);            // Set the goal flag to true
                maze.move(player, current); --steps; // move player avatar to identified goal and decrease steps
                return pathFromTo(start, current);   // With search finished: return the goal path
            }
            else {
                try {                                        // Otherwise, check if the node was already visited
                    if(!visited.contains(current) && 
                       goalIdentified.get() == false) {      // If it wasn't visited, then
                        
                        visited.add(current);                // flag current node as visited
                        maze.move(player, current); --steps; // move player avatar to the node and decrease steps
                        maze.neighbors(current).stream()
                            .map((neighbor) -> {          
                            frontier.push(neighbor);         // Add the neighbor to frontier stack
                            return neighbor;})
                            .filter((neighbor) -> 
                                (!visited.contains(neighbor))).forEach((neighbor) -> {
                                    predecessor.put(neighbor, current);
                            });
                
                       if(maze.neighbors(current).size() > 2 || maze.neighbors(current).size() == 2 && visited.size() == 1) {
                          steps = this.forkAfter;
                          List<ForkJoinSolver> forkedTasks = spawnForks();
                          for(ForkJoinSolver task : forkedTasks) {
                              List<Integer> result = task.join();
                              if(result != null) {           // if result is null and/or global path was found
                                  return Stream.concat(pathFromTo(start, current)
                                               .stream(), result.stream()).collect(Collectors.toList());
                              }
                          }
                       }
                   }
                } catch (NullPointerException e) {
                   System.out.println("Error: no more nodes to search.");
                }
            }
        }
        return null; 
    }
    
    private List<ForkJoinSolver> spawnForks() {
        List<ForkJoinSolver> spread = new ArrayList<>();
        for (int i = 0; i < this.frontier.size(); i++) {
            if (!visited.contains(frontier.get(i))) {
                spread.add(new ForkJoinSolver(this, frontier.get(i)));
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