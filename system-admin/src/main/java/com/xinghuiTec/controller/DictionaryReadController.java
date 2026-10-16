package com.xinghuiTec.controller;

import com.xinghuiTec.service.DictionaryService;
import com.xinghuiTec.service.DictionaryService.LabelView;
import com.xinghuiTec.service.DictionaryService.Option;
import com.xinghuiTec.utils.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authenticated business reads expose values, never management metadata or type listings. */
@RestController
@RequestMapping("/dict")
public class DictionaryReadController {
    private final DictionaryService dictionary;

    public DictionaryReadController(DictionaryService dictionary) {
        this.dictionary = dictionary;
    }

    @GetMapping("/{code}/options")
    public Result<List<Option>> options(@PathVariable String code) {
        return Result.ok(dictionary.options(code));
    }

    @GetMapping("/{code}/labels/{value}")
    public Result<LabelView> label(@PathVariable String code, @PathVariable String value) {
        return Result.ok(dictionary.label(code, value));
    }
}
