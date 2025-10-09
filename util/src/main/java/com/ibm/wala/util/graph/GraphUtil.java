/*
 * Copyright (c) 2007 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 */
package com.ibm.wala.util.graph;

import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.NonNullSingletonIterator;
import com.ibm.wala.util.graph.traverse.DFS;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** Utility methods for graphs. */
public class GraphUtil {

  /** count the number of edges in g */
  public static <T> long countEdges(Graph<T> g) {
    if (g == null) {
      throw new IllegalArgumentException("g is null");
    }
    long edgeCount = 0;
    for (T t : g) {
      edgeCount += g.getSuccNodeCount(t);
    }
    return edgeCount;
  }

  public static <T> Map<T, Integer> computeFinishTimes(
      Supplier<Iterator<T>> entryPoints, Graph<T> ipcfg) {
    int dfsNumber = 0;
    Map<T, Integer> dfsFinish = HashMapFactory.make();
    Iterator<T> search = DFS.iterateFinishTime(ipcfg, entryPoints.get());
    while (search.hasNext()) {
      T n = search.next();
      assert !dfsFinish.containsKey(n) : n;
      dfsFinish.put(n, dfsNumber++);
    }
    return dfsFinish;
  }

  public static <T> Map<T, Integer> computeStartTimes(
      Supplier<Iterator<T>> entryPoints, Graph<T> ipcfg) {
    int reverseDfsNumber = 0;
    Map<T, Integer> dfsStart = HashMapFactory.make();
    Iterator<T> reverseSearch = DFS.iterateDiscoverTime(ipcfg, entryPoints.get());
    while (reverseSearch.hasNext()) {
      dfsStart.put(reverseSearch.next(), reverseDfsNumber++);
    }
    return dfsStart;
  }

  public static <T> BiPredicate<T, T> fIsBackEdge(Graph<T> g, T n) {
    Map<T, Integer> cfgFinishTimes = computeFinishTimes(() -> NonNullSingletonIterator.make(n), g);
    Map<T, Integer> cfgStartTimes = computeStartTimes(() -> NonNullSingletonIterator.make(n), g);

    @SuppressWarnings("NullAway")
    BiPredicate<T, T> isBackEdge =
        (pred, succ) ->
            cfgStartTimes.containsKey(pred)
                && cfgStartTimes.containsKey(succ)
                && cfgStartTimes.get(pred) >= cfgStartTimes.get(succ)
                && cfgFinishTimes.get(pred) <= cfgFinishTimes.get(succ);

    Map<T, Set<T>> result = HashMapFactory.make();
    for (T s : g) {
      g.getPredNodes(s)
          .forEachRemaining(
              p -> {
                if (isBackEdge.test(p, s)) {
                  if (!result.containsKey(p)) {
                    result.put(p, HashSetFactory.make());
                  }
                  result.get(p).add(s);
                }
              });
    }

    return (p, s) -> result.containsKey(p) && result.get(p).contains(s);
  }
}
