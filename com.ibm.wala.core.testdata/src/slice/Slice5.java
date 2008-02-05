/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package slice;

public class Slice5 {

  public static void main(String[] args) {
    int x = baz();
    bar(x);
  }
  
  static int baz() {
    return foo(1);
  }
  
  static int foo(int x) {
    return x + 2;
  }
  
  static void bar(int x) {
    return;
  }
}
