package com.trendyol.stove.examples.java.spring.infra.components.index;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
public class IndexController {

  @RequestMapping
  public String index() {
    return "Hello, World!";
  }
}
