package com.example.online_reader.views.service;

import org.springframework.stereotype.Service;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;

@Service
public class IndexService {
    private final String pageTitle;
    private final String aboutProject;
    private final List<String> goals = new ArrayList<>();

    public IndexService() {
        pageTitle = "Добро пожаловать в ДостоRead!";
        aboutProject = "\"ДостоRead\" — это онлайн-платформа для чтения и управления электронными книгами, разработанная на основе Spring Framework. Проект создан в рамках учебного курса в университете и представляет собой аналог популярных сервисов, таких как \"Яндекс Книги\". Основная цель проекта — предоставить пользователям удобный инструмент для чтения, хранения и организации электронных книг в одном месте.\n";

        goals.add("Загрузка и хранение книг");
        goals.add("Авторизация и аутентификаци пользователей на основе прав и ролей");
        goals.add("Просмотр и чтение книг");
        goals.add("Управление прогрессом чтения");
        goals.add("Работа с заметками");
        goals.add("Управление библиотекой пользователя");
    }

    public void setUp(Model model) {
        model.addAttribute("title", pageTitle);
        model.addAttribute("aboutProject", aboutProject);
        model.addAttribute("goals", goals);
    }
}
